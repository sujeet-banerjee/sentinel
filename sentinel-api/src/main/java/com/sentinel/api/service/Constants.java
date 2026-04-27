package com.sentinel.api.service;

public interface Constants {
	String HEADER_TENANT_ID = " X-Sentinel-Tenant-ID";
	String DEFAULT_LOCAL_TENANT = "DEFAULT_LOCAL_TENANT";
	Object TENANT_ID = "TENANT_ID";
	String UNKNOWN_TENANT = "UNKNOWN_TENANT";
	String ERR_TEMPL_RATE_LIMIT_EXCEED = "RATE LIMIT EXCEEDED. Please try again in %s seconds.";
}
