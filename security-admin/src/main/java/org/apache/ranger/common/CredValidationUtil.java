package org.apache.ranger.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class CredValidationUtil {
    private static final Logger logger = LoggerFactory.getLogger(CredValidationUtil.class);

    public static final String credValidationRegex;
    private static final String VALIDATION_CRED_DEFAULT = "(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z]).{8,}";
    private static final String VALIDATION_CRED_REGEX_KEY = "ranger.admin.login.validation.regex";

    static {
        credValidationRegex = loadCredValidationRegex();
    }

    /**
     * Loads the credential validation regex from properties, defaults to VALIDATION_CRED_DEFAULT if invalid.
     *
     * @return A valid regex string for credential validation.
     */
    private static String loadCredValidationRegex() {
        String regex = PropertiesUtil.getProperty(VALIDATION_CRED_REGEX_KEY, VALIDATION_CRED_DEFAULT);
        try {
            Pattern.compile(regex);
            return regex;
        } catch (PatternSyntaxException ex) {
            logger.warn("Regular expression '{}' is incorrect. Using default value '{}'. Error: {}",
                    regex, VALIDATION_CRED_DEFAULT, ex.getMessage());
            return VALIDATION_CRED_DEFAULT;
        }
    }
}
