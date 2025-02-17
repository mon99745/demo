package com.security.jsonwebtoken.service;

import com.security.jsonwebtoken.config.RsaKeyGenerator;
import com.security.jsonwebtoken.config.VerifyProperties;
import com.security.jsonwebtoken.message.CreateTokenResponse;
import com.security.jsonwebtoken.message.ExtractClaimResponse;
import com.security.jsonwebtoken.message.VerifyTokenResponse;
import com.security.jsonwebtoken.model.Claims;
import com.security.jsonwebtoken.model.Token;
import com.security.jsonwebtoken.util.ByteUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.jsonwebtoken.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
@Service
public class TokenSerivce {
	protected final RsaKeyGenerator rsaKeyGenerator;
	protected final VerifyProperties verifyProperties;
	protected final KeyPairService keyPairService;

	/**
	 * Re-entry after Claim initialization
	 *
	 * @param requestClaim to include in JWT
	 * @return CreateTokenResponse
	 */
	public CreateTokenResponse createJwt(Map<String, String> requestClaim) {
		return this.createJwt(setClaims(requestClaim));
	}

	/**
	 * Json Web Token 생성
	 *
	 * @param claims 인가 필수 정보
	 * @return CreateTokenResponse
	 */
	public CreateTokenResponse createJwt(Claims claims) {
		try {
			if (claims == null) {
				throw new IllegalArgumentException("Claim is empty");
			}

			/** Header 생성 */
			String header = createHeader();
			log.info("header = {}, header byte = {}", header, header.getBytes().length);

			/** Payload 생성 */
			String payload = createPayload(claims);
			log.info("payload = {}, payload byte = {}", payload, payload.getBytes().length);

			/** VerifyCode 생성 */
			String verifyCode = setVerifyCode(header, payload);
			log.info("verifyCode = {}, verifyCode byte = {}", verifyCode, verifyCode.getBytes().length);

			/** Signature 생성 */
			String privateKey = keyPairService.getPrivateKey();
			String signature = createSignature(verifyCode, privateKey);
			log.info("signature = {}, signature byte = {}", signature, signature.getBytes().length);

			/** Json Web Token 생성 */
			String jwt = combineToken(header, String.join("", payload), signature);
			log.info("jwt = {}, jwt byte = {}", jwt, jwt.getBytes().length);

			return CreateTokenResponse.builder()
					.resultMsg("Success")
					.resultCode(String.valueOf(HttpStatus.OK.value()))
					.claims(claims.getPublicClaims())
					.jwt(jwt)
					.build();

		} catch (IllegalArgumentException e) {
			log.error("JWT 생성 중 예외 발생: ", e);
			return CreateTokenResponse.builder()
					.resultMsg("Fail: " + e.getMessage())
					.resultCode(String.valueOf(HttpStatus.BAD_REQUEST.value()))
					.claims(claims.getPublicClaims())
					.build();
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException |
				 NoSuchPaddingException | IllegalBlockSizeException | BadPaddingException |
				 InvalidKeyException e) {
			log.error("JWT 생성 중 예외 발생: ", e);
			return CreateTokenResponse.builder()
					.resultMsg("Fail: " + e.getMessage())
					.resultCode(String.valueOf(HttpStatus.BAD_REQUEST.value()))
					.claims(claims.getPublicClaims())
					.build();
		}
	}


	/**
	 * Json Web Token 검증
	 *
	 * @param token 검증 대상 토큰
	 * @return VerifyTokenResponse
	 */
	public VerifyTokenResponse verifyJwt(String token) {
		try {
			if (token == null || token.isEmpty()) {
				throw new IllegalArgumentException("JWT is empty");
			}

			/** 토큰 구조 분류 */
			Token tokenObject = parseToken(token);

			/** VerifyCode 생성 */
			String newVerifyCode = setVerifyCode(tokenObject.getHeader(), tokenObject.getPayload());
			log.info("verifyCode = {}, verifyCode byte = {}", newVerifyCode, newVerifyCode.getBytes().length);

			/** 서명 검증(비대칭키 복호화) */
			String publicKey = keyPairService.getPublicKey();
			String signedVerifyCode = rsaKeyGenerator.decryptPubRSA(tokenObject.getSignature(), publicKey);

			/** 위변조 검증(해시 비교) */
			if (newVerifyCode.equals(signedVerifyCode)) {
				return VerifyTokenResponse.builder()
						.resultMsg("Success")
						.resultCode(String.valueOf(HttpStatus.OK.value()))
						.jwt(token)
						.build();
			} else {
				return VerifyTokenResponse.builder()
						.resultMsg("Fail: " + "토큰이 위변조 되었습니다.")
						.resultCode(String.valueOf(HttpStatus.BAD_REQUEST.value()))
						.jwt(token)
						.build();
			}
		} catch (IllegalArgumentException e) {
			log.error("JWT 검증 중 예외 발생: ", e);
			return VerifyTokenResponse.builder()
					.resultMsg("Fail: " + e.getMessage())
					.resultCode(String.valueOf(HttpStatus.BAD_REQUEST.value()))
					.jwt(token)
					.build();
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException |
				 NoSuchPaddingException | IllegalBlockSizeException | BadPaddingException |
				 InvalidKeyException e) {
			log.error("JWT 검증 중 예외 발생: ", e);
			return VerifyTokenResponse.builder()
					.resultMsg("Fail: " + "JWT 검증 실패 :" + e.getMessage())
					.resultCode(String.valueOf(HttpStatus.BAD_REQUEST.value()))
					.jwt(token)
					.build();
		}
	}

	/**
	 * Json Web Token 정보 추출
	 *
	 * @param token 정보 추출 대상 토큰
	 * @return ExtractClaimResponse
	 */
	public ExtractClaimResponse extractClaimToJwt(String token) {
		try {
			if (token == null || token.isEmpty()) {
				throw new IllegalArgumentException("JWT is empty");
			}

			/** 토큰 구조 분류 */
			Token tokenObject = parseToken(token);

			/** 서명 검증(비대칭키 복호화) */
			String publicKey = keyPairService.getPublicKey();
			rsaKeyGenerator.decryptPubRSA(tokenObject.getSignature(), publicKey);

			/** 클레임 조회 */
			Object claims = readClaim(tokenObject.getPayload());

			return ExtractClaimResponse.builder()
					.resultMsg("Success")
					.resultCode(String.valueOf(HttpStatus.OK.value()))
					.claims(claims)
					.jwt(token)
					.build();
		} catch (IllegalArgumentException e) {
			log.error("Claim 추출 실패 : ", e);
			return ExtractClaimResponse.builder()
					.resultMsg("Fail: " + "Claim 추출 실패 : " + e.getMessage())
					.resultCode(String.valueOf(HttpStatus.BAD_REQUEST.value()))
					.build();
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException |
				 NoSuchPaddingException | IllegalBlockSizeException | BadPaddingException |
				 InvalidKeyException e) {
			log.error("JWT 검증 중 예외 발생: ", e);
			return ExtractClaimResponse.builder()
					.resultMsg("Fail: " + "Claim 추출 실패 :" + e.getMessage())
					.resultCode(String.valueOf(HttpStatus.BAD_REQUEST.value()))
					.build();
		}
	}

	public Claims setClaims(Map<String, String> requestClaim) {
		Claims.RegisteredClaim registeredClaim = Claims.RegisteredClaim.builder()
				.issuer("security.com") // 발급자
				.subject("Json Web Token") // 주제
				.expiration("2025-01-31T23:59:59Z") // 만료 시간 (ISO-8601 형식)
				.issuedAt("2025-01-21T10:00:00Z") // 발급 시간 (ISO-8601 형식)
				.build();

		Claims.PublicClaim publicClaim = Claims.PublicClaim.builder()
				.publicClaim(requestClaim)
				.build();

		return Claims.builder()
				.registeredClaims(registeredClaim)
				.publicClaims(publicClaim)
				.build();
	}

	public String createHeader() throws IOException {
		String typ = verifyProperties.getTyp();
		String alg = verifyProperties.getAlg();

		if (Objects.isNull(typ) || typ.isEmpty()
				|| Objects.isNull(alg) || alg.isEmpty()) {
			throw new RuntimeException("Header Info is null or empty");
		}

		// TODO: setHeader 정의 후 오버라이딩이 필요.
		byte[] byteHeaderData = ByteUtil.stringToBytes(typ + alg);
		String encHeader = Base58.encode(byteHeaderData);

		return encHeader;
	}

	public String createPayload(Claims claims) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		String strClaims = objectMapper.writeValueAsString(claims);
		byte[] bytePayloadData = ByteUtil.stringToBytes(strClaims);
		String payload = Base58.encode(bytePayloadData);

		return payload;
	}

	public String setVerifyCode(String header, String payload) {
		return HashUtil.sha256(header + payload);
	}


	public String createSignature(String verifyCode, String privateKey)
			throws NoSuchPaddingException, IllegalBlockSizeException, NoSuchAlgorithmException,
			InvalidKeySpecException, BadPaddingException, InvalidKeyException, UnsupportedEncodingException {

		return rsaKeyGenerator.encryptPrvRSA(verifyCode, privateKey);
	}

	public String combineToken(String header, String payload, String signature) {
		return header + "." + payload + "." + signature;
	}

	private Object readClaim(String payload) {
		ObjectMapper objectMapper = new ObjectMapper();
		byte[] decodedBytes = Base58.decode(payload);
		try {
			String strClaim = ByteUtil.bytesToUtfString(decodedBytes);
			Object json = objectMapper.readTree(strClaim);

			return json;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public Token parseToken(String token) {
		String[] splitArray = token.split("\\.");
		String header = null;
		String payload = null;
		String signature = null;

		for (int i = 0; i < splitArray.length; i++) {
			if (i == 0) {
				header = splitArray[i];
			} else if (i == 1) {
				payload = splitArray[i];
			} else if (i == 2) {
				signature = splitArray[i];
			}
		}

		return new Token(header, payload, signature);
	}
}