package com.security.jsonwebtoken.config;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * 토큰 검증 관리
 */
@Data
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ConfigurationProperties(prefix = VerifyProperties.PROPERTY_PREFIX)
public class VerifyProperties {
	/**
	 * 설정 타이틀
	 */
	public static final String PROPERTY_PREFIX = "verify";

	/**
	 * 설정 정보
	 */
	@Getter
	private static VerifyProperties instance = new VerifyProperties();

	/**
	 * 키 페어 경로
	 */
	protected String path = "./files/";

	/**
	 * 키 페어 크기
	 */
	protected int keySize = 2048;

	/**
	 * 토큰 타입
	 */
	protected String typ = "JWT";

	/**
	 * 암호화 알고리즘
	 */
	protected String alg = "RSA";
}