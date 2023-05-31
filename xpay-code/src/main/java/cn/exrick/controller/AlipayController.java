package cn.exrick.controller;

import cn.exrick.bean.Pay;
import cn.exrick.bean.dto.Result;
import cn.exrick.common.utils.EmailUtils;
import cn.exrick.common.utils.IpInfoUtils;
import cn.exrick.common.utils.ResultUtil;
import cn.exrick.common.utils.StringUtils;
import cn.exrick.service.PayService;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author Exrickx
 */
@Controller
public class AlipayController {

    private static final Logger log= LoggerFactory.getLogger(AlipayController.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PayService payService;

    @Autowired
    private EmailUtils emailUtils;

    @Value("${ip.expire}")
    private Long IP_EXPIRE;

    @Value("${my.token}")
    private String MY_TOKEN;

    @Value("${email.sender}")
    private String EMAIL_SENDER;

    @Value("${email.receiver}")
    private String EMAIL_RECEIVER;

    @Value("${server.url}")
    private String SERVER_URL;

    @Value("${token.admin.expire}")
    private Long ADMIN_EXPIRE;

    private static final String CLOSE_DMF_KEY="XPAY_CLOSE_DMF_KEY";

    private static final String CLOSE_DMF_REASON="XPAY_CLOSE_DMF_REASON";

    /**
     * 你的私钥
     */
    private static final String privateKey = "";

    /**
     * 你的公钥
     */
    private static final String publicKey = "";

    /**
     * 你的应用ID
     */
    private static final String appId = "";

    /**
     * 回调通知接口链接
     */
    private static final String notifyUrl = "http://你的域名或公网IP访问地址/alipay/notify";

    /**
     * 生成二维码
     * @param pay
     * @param request
     * @return
     * @throws AlipayApiException
     */
    @RequestMapping(value = "/alipay/precreate",method = RequestMethod.POST)
    @ResponseBody
    public Result<Object> getPayState(@ModelAttribute Pay pay, HttpServletRequest request) throws AlipayApiException {

        if(pay.getMoney()!=null&&pay.getMoney().compareTo(new BigDecimal("0.10"))!=0){
            if(pay.getMoney().compareTo(new BigDecimal("10.00"))==-1||pay.getMoney().compareTo(new BigDecimal("1000.00"))==1
                    ||StringUtils.isBlank(pay.getEmail())||!EmailUtils.checkEmail(pay.getEmail())){
                return new ResultUtil<Object>().setErrorMsg("请填写正确的通知邮箱或金额，当面付单笔金额不得大于1000");
            }
        }

        String isOpenDMF = redisTemplate.opsForValue().get(CLOSE_DMF_KEY);
        String dmfReason = redisTemplate.opsForValue().get(CLOSE_DMF_REASON);
        String msg = "";
        if(StringUtils.isNotBlank(isOpenDMF)){
            msg = dmfReason + "如有疑问请进行反馈";
            return new ResultUtil<Object>().setErrorMsg(msg);
        }
        //防炸库验证
        String ip = IpInfoUtils.getIpAddr(request);
        if("0:0:0:0:0:0:0:1".equals(ip)){
            ip = "127.0.0.1";
        }
        ip="DMF:"+ip;
        String temp = redisTemplate.opsForValue().get(ip);
        Long expire = redisTemplate.getExpire(ip, TimeUnit.SECONDS);
        if(StringUtils.isNotBlank(temp)){
            return new ResultUtil<Object>().setErrorMsg("您提交的太频繁啦，作者的学生服务器要炸啦！请"+expire+"秒后再试");
        }
        payService.addPay(pay);
        //记录缓存
        redisTemplate.opsForValue().set(ip,"added", 1L, TimeUnit.MINUTES);

        AlipayClient alipayClient = new DefaultAlipayClient("https://openapi.alipay.com/gateway.do",
                appId,privateKey,"json","GBK",publicKey,"RSA2");
        AlipayTradePrecreateRequest r = new AlipayTradePrecreateRequest();
        r.setBizContent("{" +
                "\"out_trade_no\":\""+pay.getId()+"\"," +
                "\"total_amount\":"+pay.getMoney()+"," +
                "\"subject\":\"XPay-向作者Exrick捐赠\"" +
                "  }");
        // 设置通知回调链接
        r.setNotifyUrl(notifyUrl);
        AlipayTradePrecreateResponse response = alipayClient.execute(r);
        if(!response.isSuccess()){
            return new ResultUtil<Object>().setErrorMsg("调用支付宝接口生成二维码失败，请向作者反馈");
        }
        Map<String, Object> result = new HashMap<>(16);
        result.put("id", pay.getId());
        result.put("qrCode", response.getQrCode());
        return new ResultUtil<Object>().setData(result);
    }

    /**
     * 查询支付结果
     * @param out_trade_no
     * @return
     * @throws AlipayApiException
     */
    @RequestMapping(value = "/alipay/query/{out_trade_no}",method = RequestMethod.GET)
    @ResponseBody
    public Result<Object> queryPayState(@PathVariable String out_trade_no) throws AlipayApiException {

        AlipayClient alipayClient = new DefaultAlipayClient("https://openapi.alipay.com/gateway.do",
                appId,privateKey,"json","GBK",publicKey,"RSA2");
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        request.setBizContent("{" +
                "\"out_trade_no\":\""+out_trade_no+"\"" +
                "  }");
        AlipayTradeQueryResponse response = alipayClient.execute(request);
        if(response!=null&&response.isSuccess()&&"TRADE_SUCCESS".equals(response.getTradeStatus())){
            sendActiveEmail(out_trade_no);
            return new ResultUtil<Object>().setData(1);
        }else{
            return new ResultUtil<Object>().setData(0);
        }
    }

    /**
     * 支付宝通知回调
     * @return
     */
    @RequestMapping(value = "/alipay/notify")
    @ResponseBody
    public String notify(@RequestParam(required = false) String out_trade_no,
                         @RequestParam(required = false) String trade_status) {
        
        if("TRADE_SUCCESS".equals(trade_status)){
            sendActiveEmail(out_trade_no);
        }
        return "success";
    }

    @Async
    public void sendActiveEmail(String id){

        Pay pay = payService.getPay(id);
        if(pay.getState()==1){
            return;
        }
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set("dmf:"+pay.getId(), token, ADMIN_EXPIRE, TimeUnit.DAYS);
        redisTemplate.opsForValue().set(pay.getId(), token, ADMIN_EXPIRE, TimeUnit.DAYS);
        pay = getAdminUrl(pay, pay.getId(), token, MY_TOKEN);

        if(pay.getMoney().compareTo(new BigDecimal("0.99"))==1){
            emailUtils.sendTemplateMail(EMAIL_SENDER,EMAIL_RECEIVER,"【XPay支付系统】当面付收款"+pay.getMoney()+"元","email-admin",pay);
        }
        if(pay.getMoney().compareTo(new BigDecimal("9.99"))==1&&pay.getMoney().compareTo(new BigDecimal("68.00"))==-1){
            // 发送xpay
            emailUtils.sendTemplateMail(EMAIL_SENDER, pay.getEmail(),"【XPay个人收款支付系统】支付成功通知（附下载链接）","pay-success", pay);
        }else if(pay.getMoney().compareTo(new BigDecimal("198.00"))==0||pay.getMoney().compareTo(new BigDecimal("198.00"))==1){
            // 发送xboot
            emailUtils.sendTemplateMail(EMAIL_SENDER, pay.getEmail(),"【XPay个人收款支付系统】支付成功通知（附下载链接）","sendxboot", pay);
        }
        pay.setState(1);
        payService.updatePay(pay);
        redisTemplate.delete("xpay:"+pay.getId());
    }

    /**
     * 拼接管理员链接
     */
    public Pay getAdminUrl(Pay pay,String id,String token,String myToken){

        String pass=SERVER_URL+"/pay/pass?sendType=0&id="+id+"&token="+token+"&myToken="+myToken;
        pay.setPassUrl(pass);

        String pass2=SERVER_URL+"/pay/pass?sendType=1&id="+id+"&token="+token+"&myToken="+myToken;
        pay.setPassUrl2(pass2);

        String pass3=SERVER_URL+"/pay/pass?sendType=2&id="+id+"&token="+token+"&myToken="+myToken;
        pay.setPassUrl3(pass3);

        String back=SERVER_URL+"/pay/back?id="+id+"&token="+token+"&myToken="+myToken;
        pay.setBackUrl(back);

        String passNotShow=SERVER_URL+"/pay/passNotShow?id="+id+"&token="+token;
        pay.setPassNotShowUrl(passNotShow);

        String edit=SERVER_URL+"/pay-edit?id="+id+"&token="+token;
        pay.setEditUrl(edit);

        String del=SERVER_URL+"/pay-del?id="+id+"&token="+token;
        pay.setDelUrl(del);

        String close=SERVER_URL+"/pay-close?id="+id+"&token="+token;
        pay.setCloseUrl(close);

        String statistic=SERVER_URL+"/statistic?myToken="+myToken;
        pay.setStatistic(statistic);

        return pay;
    }
}
