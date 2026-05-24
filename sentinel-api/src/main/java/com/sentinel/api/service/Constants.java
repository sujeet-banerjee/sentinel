package com.sentinel.api.service;

import java.time.Duration;

public interface Constants {
	String HEADER_TENANT_ID = " X-Sentinel-Tenant-ID";
	String DEFAULT_LOCAL_TENANT = "DEFAULT_LOCAL_TENANT";
	String TENANT_ID = "TENANT_ID";
	String SESSION_ID = "SESSION_ID";
	String UNKNOWN_TENANT = "UNKNOWN_TENANT";
	
	/*
	 * - - - - KEY TEMPLATES - - - - 
	 */
	
	/**
	 * Format ==> 
	 * 		"sentinel:memory:tenant:" + tenantId + ":session:" + sessionId
	 */
	String KEY_TMPL_SESSION_MEM_KEY =  "sentinel:memory:tenant:%s:session:%s";
	
	/*
	 * - - - - ERROR MESSAGE TEMPLATES - - - - 
	 */
	
	String ERR_TEMPL_RATE_LIMIT_EXCEED = "RATE LIMIT EXCEEDED. Please try again in %s seconds.";
	/**
	 *  Kill the session memory if idle for 2 hours
	 */
	Duration SESSION_TTL = Duration.ofHours(2);
	/**
	 *  Keep the last 10 messages (5 user prompts, 5 AI responses)
	 */
	int MAX_HISTORY_MESSAGES = 10;
	/*
	 *  In a production app, this would be loaded dynamically from a DB per tenant.
	 *  We are hardcoding a strict limit of 5 requests per minute for this demonstration.
	 *  
	 *  TODO move to DB (config)
	 */
	int MAX_REQUESTS_PER_MINUTE = 5;
	
}
