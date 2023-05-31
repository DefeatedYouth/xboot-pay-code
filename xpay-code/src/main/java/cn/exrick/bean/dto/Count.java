package cn.exrick.bean.dto;

import java.math.BigDecimal;

public class Count {

    private BigDecimal amount = new BigDecimal("0.00");

    private BigDecimal weixin = new BigDecimal("0.00");

    private BigDecimal alipay = new BigDecimal("0.00");

    private BigDecimal wechat = new BigDecimal("0.00");

    private BigDecimal qq = new BigDecimal("0.00");

    private BigDecimal union = new BigDecimal("0.00");

    private BigDecimal diandan = new BigDecimal("0.00");

    private BigDecimal dmf = new BigDecimal("0.00");

    public BigDecimal getWeixin() {
        return weixin;
    }

    public void setWeixin(BigDecimal weixin) {
        this.weixin = weixin;
    }

    public BigDecimal getDmf() {
        return dmf;
    }

    public void setDmf(BigDecimal dmf) {
        this.dmf = dmf;
    }

    public BigDecimal getDiandan() {
        return diandan;
    }

    public void setDiandan(BigDecimal diandan) {
        this.diandan = diandan;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getAlipay() {
        return alipay;
    }

    public void setAlipay(BigDecimal alipay) {
        this.alipay = alipay;
    }

    public BigDecimal getWechat() {
        return wechat;
    }

    public void setWechat(BigDecimal wechat) {
        this.wechat = wechat;
    }

    public BigDecimal getQq() {
        return qq;
    }

    public void setQq(BigDecimal qq) {
        this.qq = qq;
    }

    public BigDecimal getUnion() {
        return union;
    }

    public void setUnion(BigDecimal union) {
        this.union = union;
    }
}
