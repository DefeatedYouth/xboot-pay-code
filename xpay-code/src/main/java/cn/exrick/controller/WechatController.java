package cn.exrick.controller;

import cn.exrick.bean.Pay;
import cn.exrick.bean.dto.Result;
import cn.exrick.common.utils.*;
import cn.exrick.service.PayService;
import com.alipay.api.AlipayApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author Exrickx
 */
@Controller
public class WechatController {

    private static final Logger log= LoggerFactory.getLogger(WechatController.class);

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

    private static final String CLOSE_WECHAT_KEY="XPAY_CLOSE_WECHAT_KEY";

    private static final String CLOSE_WECHAT_REASON="XPAY_CLOSE_WECHAT_REASON";

    /**
     * 你的appid
     */
    private static final String appid = "";

    /**
     * 你的商户id
     */
    private static final String mch_id = "";

    /**
     * 你的微信支付秘钥key
     */
    private static final String key = "";

    /**
     * 回调通知接口链接
     */
    private static final String notifyUrl = "http://你的域名或公网IP访问地址/wechat/notify";

    /**
     * 生成二维码
     * @param pay
     * @param request
     * @return
     * @throws AlipayApiException
     */
    @RequestMapping(value = "/wechat/precreate",method = RequestMethod.POST)
    @ResponseBody
    public Result<Object> getPayState(@ModelAttribute Pay pay, HttpServletRequest request) {

        if(pay.getMoney()!=null&&pay.getMoney().compareTo(new BigDecimal("0.10"))!=0){
            if(pay.getMoney().compareTo(new BigDecimal("10.00"))==-1
                    ||StringUtils.isBlank(pay.getEmail())||!EmailUtils.checkEmail(pay.getEmail())){
                return new ResultUtil<Object>().setErrorMsg("请填写正确的通知邮箱或金额");
            }
        }

        String isOpenWechat = redisTemplate.opsForValue().get(CLOSE_WECHAT_KEY);
        String wechatReason = redisTemplate.opsForValue().get(CLOSE_WECHAT_REASON);
        String msg = "";
        if(StringUtils.isNotBlank(isOpenWechat)){
            msg = wechatReason + "如有疑问请进行反馈";
            return new ResultUtil<Object>().setErrorMsg(msg);
        }
        //防炸库验证
        String ip = IpInfoUtils.getIpAddr(request);
        if("0:0:0:0:0:0:0:1".equals(ip)){
            ip = "127.0.0.1";
        }
        String ipkey = "WECHAT:"+ip;
        String temp = redisTemplate.opsForValue().get(ipkey);
        Long expire = redisTemplate.getExpire(ipkey, TimeUnit.SECONDS);
        if(StringUtils.isNotBlank(temp)){
            return new ResultUtil<Object>().setErrorMsg("您提交的太频繁啦，作者的学生服务器要炸啦！请"+expire+"秒后再试");
        }
        payService.addPay(pay);
        //记录缓存
        redisTemplate.opsForValue().set(ipkey,"added", 1L, TimeUnit.MINUTES);

        String wbody = "XPay-向作者Exrick捐赠";
        String wnonce_str = String.valueOf(System.currentTimeMillis());
        BigDecimal wtotal_fee = pay.getMoney().multiply(new BigDecimal("100.00")).setScale(0);

        // 待签名字符串
        String toSign = "appid="+appid+"&body="+wbody+"&mch_id="+mch_id+"&nonce_str="+wnonce_str+"&" +
                "notify_url="+notifyUrl+"&out_trade_no="+pay.getId()+"&spbill_create_ip="+ip+"&" +
                "total_fee="+wtotal_fee+"&trade_type=NATIVE&key="+key;
        // 签名
        String sign = WXPayUtil.MD5(toSign);

        String body = "<xml>\n" +
                "   <appid>"+appid+"</appid>\n" +
                "   <mch_id>"+mch_id+"</mch_id>\n" +
                "   <nonce_str>"+wnonce_str+"</nonce_str>\n" +
                "   <body>"+wbody+"</body>\n" +
                "   <out_trade_no>"+pay.getId()+"</out_trade_no>\n" +
                "   <total_fee>"+wtotal_fee+"</total_fee>\n" +
                "   <spbill_create_ip>"+ip+"</spbill_create_ip>\n" +
                "   <notify_url>"+notifyUrl+"</notify_url>\n" +
                "   <trade_type>NATIVE</trade_type>\n" +
                "   <sign>"+sign+"</sign>\n" +
                "</xml>";

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().set(1, new StringHttpMessageConverter(StandardCharsets.UTF_8));
        ResponseEntity<String> res = restTemplate.postForEntity("https://api.mch.weixin.qq.com/pay/unifiedorder", body, String.class);
        Map<String, String> map = WXPayUtil.xmlToMap(res.getBody());

        if("FAIL".equals(map.get("return_code"))){
            return new ResultUtil<Object>().setErrorMsg(map.get("return_msg"));
        }

        Map<String, Object> result = new HashMap<>(16);
        result.put("id", pay.getId());
        result.put("qrCode", map.get("code_url"));
        return new ResultUtil<Object>().setData(result);
    }

    /**
     * 查询支付结果
     * @param out_trade_no
     * @return
     * @throws AlipayApiException
     */
    @RequestMapping(value = "/wechat/query/{out_trade_no}",method = RequestMethod.GET)
    @ResponseBody
    public Result<Object> queryPayState(@PathVariable String out_trade_no) {

        String wnonce_str = String.valueOf(System.currentTimeMillis());
        // 待签名字符串
        String toSign = "appid="+appid+"&mch_id="+mch_id+"&nonce_str="+wnonce_str+"&out_trade_no="+out_trade_no+"&key="+key;
        // 签名
        String sign = WXPayUtil.MD5(toSign);

        String body = "<xml>\n" +
                "   <appid>"+appid+"</appid>\n" +
                "   <mch_id>"+mch_id+"</mch_id>\n" +
                "   <nonce_str>"+wnonce_str+"</nonce_str>\n" +
                "   <out_trade_no>"+out_trade_no+"</out_trade_no>\n" +
                "   <sign>"+sign+"</sign>\n" +
                "</xml>";

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().set(1, new StringHttpMessageConverter(StandardCharsets.UTF_8));
        ResponseEntity<String> res = restTemplate.postForEntity("https://api.mch.weixin.qq.com/pay/orderquery", body, String.class);
        Map<String, String> map = WXPayUtil.xmlToMap(res.getBody());

        if("SUCCESS".equals(map.get("trade_state"))){
            sendActiveEmail(out_trade_no);
            return new ResultUtil<Object>().setData(1);
        }else{
            return new ResultUtil<Object>().setData(0);
        }
    }

    /**
     * 微信通知回调
     * @return
     * @throws AlipayApiException
     */
    @RequestMapping(value = "/wechat/notify")
    @ResponseBody
    public String notify(HttpServletRequest request) {

        try {
            InputStream in = request.getInputStream();
            BufferedReader br = new BufferedReader( new InputStreamReader( in, "UTF-8" ) );
            StringBuffer result = new StringBuffer();
            String line = "";
            while ((line = br.readLine()) != null ) {
                result.append(line);
            }
            Map<String, String> map = WXPayUtil.xmlToMap(result.toString());

            if("SUCCESS".equals(map.get("result_code"))){
                sendActiveEmail(map.get("out_trade_no"));
            }
            return "<xml>\n" +
                    "\n" +
                    "  <return_code><![CDATA[SUCCESS]]></return_code>\n" +
                    "  <return_msg><![CDATA[OK]]></return_msg>\n" +
                    "</xml>";
        } catch (Exception e){
            return "error";
        }
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
            emailUtils.sendTemplateMail(EMAIL_SENDER,EMAIL_RECEIVER,"【XPay支付系统】微信支付收款"+pay.getMoney()+"元","email-admin",pay);
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
