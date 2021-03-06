package com.frameworkium.core.ui;

import com.frameworkium.core.common.properties.Property;
import com.frameworkium.core.common.reporting.TestIdUtils;
import com.frameworkium.core.common.reporting.allure.AllureProperties;
import com.frameworkium.core.ui.browsers.UserAgent;
import com.frameworkium.core.ui.capture.ScreenshotCapture;
import com.frameworkium.core.ui.driver.DriverSetup;
import com.frameworkium.core.ui.driver.lifecycle.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.*;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Wait;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import static java.time.temporal.ChronoUnit.SECONDS;

/**
 * Handles all UI test related state and life cycle.
 * Contains a ThreadLocal instance
 */
public class UITestLifecycle {

    private static final Duration DEFAULT_TIMEOUT = Duration.of(10, SECONDS);

    private static final ThreadLocal<ScreenshotCapture> capture = new ThreadLocal<>();
    private static final ThreadLocal<Wait<WebDriver>> wait = new ThreadLocal<>();
    private static final ThreadLocal<UITestLifecycle> uiTestLifecycle = ThreadLocal.withInitial(UITestLifecycle::new);

    private static DriverLifecycle driverLifecycle;
    private static String userAgent;

    /** @return a ThreadLocal instance of {@link UITestLifecycle} */
    public static UITestLifecycle get() {
        return uiTestLifecycle.get();
    }

    /** @return check to see if class initialised correctly. */
    public boolean isInitialised() {
        return wait.get() != null;
    }

    /** Run this before the test suite to initialise a pool of drivers. */
    public void beforeSuite() {
        if (Property.REUSE_BROWSER.getBoolean()) {
            driverLifecycle =
                    new MultiUseDriverLifecycle(
                            Property.THREADS.getIntWithDefault(1));
        } else {
            driverLifecycle = new SingleUseDriverLifecycle();
        }
        driverLifecycle.initDriverPool(DriverSetup::instantiateDriver);
    }

    /**
     * Run this before each test method to initialise the
     * browser, wait, capture, and user agent.
     *
     * <p>This is useful for times when the testMethod does not contain the required
     * test name e.g. using data providers for BDD.
     *
     * @param testName the test name for Capture
     */
    public void beforeTestMethod(String testName) {
        driverLifecycle.initBrowserBeforeTest(DriverSetup::instantiateDriver);

        wait.set(newWaitWithTimeout(DEFAULT_TIMEOUT));

        if (ScreenshotCapture.isRequired()) {
            capture.set(new ScreenshotCapture(testName));
        }

        if (userAgent == null) {
            userAgent = UserAgent.getUserAgent((JavascriptExecutor) getWebDriver());
        }
    }

    /**
     * @param testMethod the method about to run, used to extract the test name
     * @see #beforeTestMethod(String)
     */
    public void beforeTestMethod(Method testMethod) {
        beforeTestMethod(getTestNameForCapture(testMethod));
    }

    private String getTestNameForCapture(Method testMethod) {
        Optional<String> testID = TestIdUtils.getIssueOrTmsLinkValue(testMethod);
        if (!testID.isPresent() || testID.get().isEmpty()) {
            testID = Optional.of(StringUtils.abbreviate(testMethod.getName(), 20));
        }
        return testID.orElse("n/a");
    }

    /** Run after each test method to clear or tear down the browser */
    public void afterTestMethod() {
        driverLifecycle.tearDownDriver();
    }

    /**
     * Run after the entire test suite to:
     * clear down the browser pool, send remaining screenshots to Capture
     * and create properties for Allure.
     */
    public void afterTestSuite() {
        driverLifecycle.tearDownDriverPool();
        ScreenshotCapture.processRemainingBacklog();
        AllureProperties.createUI();
    }

    /**
     * @return new Wait with default timeout.
     * @deprecated use {@code UITestLifecycle.get().getWait()} instead.
     */
    @Deprecated
    public Wait<WebDriver> newDefaultWait() {
        return newWaitWithTimeout(DEFAULT_TIMEOUT);
    }

    /**
     * @param timeout timeout for the new Wait
     * @return a Wait with the given timeout
     */
    public Wait<WebDriver> newWaitWithTimeout(Duration timeout) {
        return new FluentWait<>(getWebDriver())
                .withTimeout(timeout)
                .ignoring(NoSuchElementException.class)
                .ignoring(StaleElementReferenceException.class);
    }

    public WebDriver getWebDriver() {
        return driverLifecycle.getWebDriver();
    }

    public ScreenshotCapture getCapture() {
        return capture.get();
    }

    public Wait<WebDriver> getWait() {
        return wait.get();
    }

    /**
     * @return the user agent of the browser in the first UI test to run.
     */
    public Optional<String> getUserAgent() {
        return Optional.ofNullable(userAgent);
    }

    /** @return the session ID of the remote WebDriver */
    public String getRemoteSessionId() {
        return Objects.toString(((RemoteWebDriver) getWebDriver()).getSessionId());
    }
}
