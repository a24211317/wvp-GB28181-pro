package com.genersoft.iot.vmp.gb28181.transmit.event.response.impl;

import com.genersoft.iot.vmp.gb28181.bean.Platform;
import com.genersoft.iot.vmp.gb28181.bean.PlatformCatch;
import com.genersoft.iot.vmp.gb28181.bean.SipTransactionInfo;
import com.genersoft.iot.vmp.gb28181.transmit.SIPProcessorObserver;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommanderForPlatform;
import com.genersoft.iot.vmp.gb28181.transmit.event.response.SIPResponseProcessorAbstract;
import com.genersoft.iot.vmp.gb28181.service.IPlatformService;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.dao.dto.PlatformRegisterInfo;
import gov.nist.javax.sip.message.SIPResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.InvalidArgumentException;
import javax.sip.ResponseEvent;
import javax.sip.SipException;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.Response;
import java.text.ParseException;

/**    
 * @description:Register响应处理器
 * @author: swwheihei
 * @date:   2020年5月3日 下午5:32:23     
 */
@Slf4j
@Component
public class RegisterResponseProcessor extends SIPResponseProcessorAbstract {

	private final String method = "REGISTER";

	@Autowired
	private ISIPCommanderForPlatform sipCommanderForPlatform;

	@Autowired
	private IRedisCatchStorage redisCatchStorage;

	@Autowired
	private SIPProcessorObserver sipProcessorObserver;

	@Autowired
	private IPlatformService platformService;

	@Override
	public void afterPropertiesSet() throws Exception {
		// 添加消息处理的订阅
		sipProcessorObserver.addResponseProcessor(method, this);
	}

	/**
	 * 处理Register响应
	 *
 	 * @param evt 事件
	 */
	@Override
	public void process(ResponseEvent evt) {
		SIPResponse response = (SIPResponse)evt.getResponse();
		String callId = response.getCallIdHeader().getCallId();
		PlatformRegisterInfo platformRegisterInfo = redisCatchStorage.queryPlatformRegisterInfo(callId);
		if (platformRegisterInfo == null) {
			log.info(String.format("[国标级联]未找到callId： %s 的注册/注销平台id", callId ));
			return;
		}

		PlatformCatch parentPlatformCatch = redisCatchStorage.queryPlatformCatchInfo(platformRegisterInfo.getPlatformId());
		if (parentPlatformCatch == null) {
			log.warn(String.format("[国标级联]收到注册/注销%S请求，平台：%s，但是平台缓存信息未查询到!!!", response.getStatusCode(),platformRegisterInfo.getPlatformId()));
			return;
		}

		String action = platformRegisterInfo.isRegister() ? "注册" : "注销";
		log.info(String.format("[国标级联]%s %S响应,%s ", action, response.getStatusCode(), platformRegisterInfo.getPlatformId() ));
		Platform parentPlatform = parentPlatformCatch.getPlatform();
		if (parentPlatform == null) {
			log.warn(String.format("[国标级联]收到 %s %s的%S请求, 但是平台信息未查询到!!!", platformRegisterInfo.getPlatformId(), action, response.getStatusCode()));
			return;
		}

		if (response.getStatusCode() == Response.UNAUTHORIZED) {
			WWWAuthenticateHeader www = (WWWAuthenticateHeader)response.getHeader(WWWAuthenticateHeader.NAME);
			SipTransactionInfo sipTransactionInfo = new SipTransactionInfo(response);
			try {
				sipCommanderForPlatform.register(parentPlatform, sipTransactionInfo, www, null, null, platformRegisterInfo.isRegister());
			} catch (SipException | InvalidArgumentException | ParseException e) {
				log.error("[命令发送失败] 国标级联 再次注册: {}", e.getMessage());
			}
		}else if (response.getStatusCode() == Response.OK){

			if (platformRegisterInfo.isRegister()) {
				SipTransactionInfo sipTransactionInfo = new SipTransactionInfo(response);
				platformService.online(parentPlatform, sipTransactionInfo);
			}else {
				platformService.offline(parentPlatform, true);
			}

			// 注册/注销成功移除缓存的信息
			redisCatchStorage.delPlatformRegisterInfo(callId);
		}
	}

}
