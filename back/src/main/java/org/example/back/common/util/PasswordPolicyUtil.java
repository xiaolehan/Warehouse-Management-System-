package org.example.back.common.util;

import cn.hutool.crypto.digest.BCrypt;
import org.example.back.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

public final class PasswordPolicyUtil {

    /**
     * 新账号未填写初始密码时使用的默认密码
     */
    public static final String DEFAULT_INITIAL_PASSWORD = "123456";

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 20;
    private static final Pattern LETTER_PATTERN = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d");

    private PasswordPolicyUtil() {
    }

    public static void validateUserPassword(String password, String fieldLabel) {
        String label = StringUtils.hasText(fieldLabel) ? fieldLabel.trim() : "密码";
        if (!StringUtils.hasText(password)) {
            throw BusinessException.validateFail(label + "不能为空");
        }
        if (!password.strip().equals(password)) {
            throw BusinessException.validateFail(label + "首尾不能包含空格");
        }
        if (password.length() < MIN_LENGTH
                || password.length() > MAX_LENGTH
                || !LETTER_PATTERN.matcher(password).find()
                || !DIGIT_PATTERN.matcher(password).find()) {
            throw BusinessException.validateFail(label + "长度为8–20位，须同时包含字母和数字");
        }
    }

    /**
     * 解析初始密码：填写则校验密码规则后哈希，不填使用默认密码
     */
    public static String resolveInitialPassword(String rawPassword) {
        if (StringUtils.hasText(rawPassword)) {
            validateUserPassword(rawPassword, "初始密码");
            return BCrypt.hashpw(rawPassword);
        }
        return BCrypt.hashpw(DEFAULT_INITIAL_PASSWORD);
    }
}
