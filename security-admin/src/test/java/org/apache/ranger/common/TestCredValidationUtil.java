package org.apache.ranger.common;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TestCredValidationUtil {

    @Test
    public void testDefaultRegexValue() {
        String expected = "(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z]).{8,}";
        Assert.assertEquals("Default regex should be loaded", expected, CredValidationUtil.credValidationRegex);
    }
}
