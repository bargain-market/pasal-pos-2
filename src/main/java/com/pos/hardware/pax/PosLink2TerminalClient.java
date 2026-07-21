package com.pos.hardware.pax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * POSLink 2 adapter loaded via reflection so the project compiles without SDK JARs.
 * Drop POSLink JARs into {@code lib/pax/} for live terminal communication.
 *
 * <p>Written against the documented PAX POSLink Java API:
 * <pre>
 *   CommSetting cs = new CommSetting();
 *   cs.setType("TCP"); cs.setDestIP(...); cs.setDestPort(...); cs.setTimeOut(...);
 *   PosLink link = new PosLink();
 *   link.SetCommSetting(cs);
 *   PaymentRequest req = new PaymentRequest();
 *   req.TenderType = req.ParseTenderType("CREDIT");
 *   req.TransType  = req.ParseTransType("SALE");
 *   req.Amount = "&lt;cents&gt;"; req.ECRRefNum = "...";
 *   link.PaymentRequest = req;
 *   ProcessTransResult r = link.ProcessTrans();      // r.Code == OK means comm succeeded
 *   PaymentResponse resp = link.PaymentResponse;     // resp.ResultCode == "000000" means approved
 * </pre>
 * Field-vs-setter and method-name fallbacks are kept so older/newer SDK variants still resolve.
 */
public class PosLink2TerminalClient implements PaxTerminalClient {

    private static final Logger logger = LoggerFactory.getLogger(PosLink2TerminalClient.class);

    /** POSLink protocol code for an approved transaction. */
    private static final String APPROVED_RESULT_CODE = "000000";

    private static final String[] POS_LINK_CLASS_NAMES = {
            "com.pax.poslink.PosLink",
            "com.pax.poslink.poslink.PosLink",
            "POSLink.PosLink"
    };

    private static final String[] COMM_SETTING_CLASS_NAMES = {
            "com.pax.poslink.CommSetting",
            "com.pax.poslink.commsetting.CommSetting"
    };

    private static final String[] PAYMENT_REQUEST_CLASS_NAMES = {
            "com.pax.poslink.PaymentRequest",
            "com.pax.poslink.payment.PaymentRequest"
    };

    private static final String POS_LINK_SEMI_CLASS = "com.pax.poslinksemiintegration.POSLinkSemi";

    private enum SdkMode {
        LEGACY,
        SEMI_INTEGRATION
    }

    private static volatile Boolean sdkPresentCache;

    private final PaxCommSettings settings;
    private final ClassLoader sdkClassLoader;
    private final SdkMode sdkMode;
    private final Class<?> posLinkClass;
    private final Class<?> commSettingClass;
    private final Class<?> paymentRequestClass;

    public PosLink2TerminalClient(PaxCommSettings settings) throws PaxTerminalException {
        this.settings = settings;
        this.sdkClassLoader = buildSdkClassLoader();
        if (tryResolveClass(POS_LINK_SEMI_CLASS) != null) {
            this.sdkMode = SdkMode.SEMI_INTEGRATION;
            this.posLinkClass = null;
            this.commSettingClass = null;
            this.paymentRequestClass = null;
        } else {
            this.sdkMode = SdkMode.LEGACY;
            this.posLinkClass = resolveClass(POS_LINK_CLASS_NAMES);
            this.commSettingClass = resolveClass(COMM_SETTING_CLASS_NAMES);
            this.paymentRequestClass = resolveClass(PAYMENT_REQUEST_CLASS_NAMES);
        }
    }

    /** Cached SDK-presence check — {@link #buildSdkClassLoader()} scans the disk, so avoid repeating it. */
    public static boolean isSdkPresent() {
        Boolean cached = sdkPresentCache;
        if (cached != null) {
            return cached;
        }
        boolean present = detectSdk();
        sdkPresentCache = present;
        return present;
    }

    /** Forces the next {@link #isSdkPresent()} to re-scan (call after JARs may have changed). */
    public static void clearSdkPresenceCache() {
        sdkPresentCache = null;
    }

    /**
     * Human-readable reason when {@link #isSdkPresent()} is false.
     * Distinguishes missing JARs from the common Android Peripheries mismatch.
     */
    public static String getSdkLoadHint() {
        Path libDir = resolveLibDir();
        if (!Files.isDirectory(libDir)) {
            return "No lib/pax/ folder — copy POSLink 2 Semi-Integration Java JARs there and restart.";
        }
        List<String> jarNames = listJarNames(libDir);
        if (jarNames.isEmpty()) {
            return "lib/pax/ is empty — copy POSLink 2 Semi-Integration Java JARs there and restart.";
        }
        boolean hasAndroidOnly = jarNames.stream()
                .anyMatch(name -> name.contains("Android") || name.contains("Peripheries"))
                && jarNames.stream().noneMatch(name -> name.contains("_Java_"));
        if (hasAndroidOnly) {
            return "Wrong SDK in lib/pax/ (Android Peripheries). Download POSLink 2 Semi-Integration "
                    + "Java from the PAX Developer Center — see lib/pax/README.md.";
        }
        return "JARs in lib/pax/ do not contain a supported POSLink payment API — use POSLink 2 "
                + "Semi-Integration Java and restart.";
    }

    private static boolean detectSdk() {
        try {
            ClassLoader loader = createSdkClassLoader();
            if (Class.forName(POS_LINK_SEMI_CLASS, false, loader) != null) {
                return true;
            }
            for (String name : POS_LINK_CLASS_NAMES) {
                if (Class.forName(name, false, loader) != null) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("PAX SDK not available: {}", e.getMessage());
        }
        return false;
    }

    @Override
    public boolean isAvailable() {
        return settings.isConfigured();
    }

    @Override
    public PaxPaymentResult processSale(BigDecimal amount, String invoiceRef) throws PaxTerminalException {
        if (!isAvailable()) {
            throw new PaxTerminalException("PAX terminal is not configured");
        }

        if (sdkMode == SdkMode.SEMI_INTEGRATION) {
            return processSaleSemiIntegration(amount, invoiceRef);
        }

        try {
            Object posLink = posLinkClass.getDeclaredConstructor().newInstance();

            Object commSetting = commSettingClass.getDeclaredConstructor().newInstance();
            applyCommSetting(commSetting);
            // POSLink exposes SetCommSetting(commSetting); fall back to a CommSetting field on older builds.
            if (!invokeSetter(posLink, commSetting, "SetCommSetting", "setCommSetting")) {
                setFieldOrProperty(posLink, "CommSetting", commSetting);
            }

            Object paymentRequest = paymentRequestClass.getDeclaredConstructor().newInstance();
            setPaymentRequestFields(paymentRequest, amount, invoiceRef);
            setFieldOrProperty(posLink, "PaymentRequest", paymentRequest);

            Method processTrans = posLinkClass.getMethod("ProcessTrans");
            Object processResult = processTrans.invoke(posLink);
            if (processResult == null) {
                return PaxPaymentResult.declined("No response from PAX terminal", "NO_RESPONSE");
            }

            // ProcessTransResult.Code == OK only means the comm round-trip worked, NOT that the card
            // was approved. A non-OK code is a terminal/communication problem (timeout, cancel, error).
            String commCode = readProcessResultCode(processResult);
            if (!"OK".equals(commCode)) {
                String detail = readStringProperty(processResult, "Msg", "Message");
                return PaxPaymentResult.declined(describeCommFailure(commCode, detail),
                        commCode != null ? commCode : "ERROR");
            }

            // Approval is decided by PaymentResponse.ResultCode ("000000"). PaymentResponse is a public
            // field on PosLink (populated after ProcessTrans), not a getter on most SDK builds.
            Object paymentResponse = readObject(posLink, "PaymentResponse", "GetPaymentResponse", "getPaymentResponse");
            return mapPaymentResponse(paymentResponse, invoiceRef);
        } catch (PaxTerminalException e) {
            throw e;
        } catch (Exception e) {
            throw new PaxTerminalException("PAX payment failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String testConnection() throws PaxTerminalException {
        if (!settings.isConfigured()) {
            throw new PaxTerminalException("PAX terminal host/port not configured");
        }
        PaxPaymentResult ping = processSale(new BigDecimal("0.01"), "PAXTEST" + System.currentTimeMillis());
        if (ping.approved) {
            return "Connected to PAX terminal at " + settings.host + ":" + settings.port;
        }
        return "Terminal reachable but test transaction declined: " + ping.message;
    }

    private PaxPaymentResult processSaleSemiIntegration(BigDecimal amount, String invoiceRef)
            throws PaxTerminalException {
        try {
            Class<?> semiClass = resolveClass(POS_LINK_SEMI_CLASS);
            Object semi = semiClass.getMethod("getInstance").invoke(null);

            Object commSetting = createSemiIntegrationCommSetting();

            Class<?> commSettingClass = resolveClass("com.pax.poscore.commsetting.CommunicationSetting");
            Object terminal = semiClass.getMethod("getTerminal", commSettingClass).invoke(semi, commSetting);
            if (terminal == null) {
                throw new PaxTerminalException(describeTerminalConnectFailure(commSetting));
            }
            Object transaction = terminal.getClass().getMethod("getTransaction").invoke(terminal);

            Class<?> doCreditRequestClass =
                    resolveClass("com.pax.poslinksemiintegration.transaction.DoCreditRequest");
            Object request = doCreditRequestClass.getDeclaredConstructor().newInstance();

            Class<?> transactionTypeClass = resolveClass("com.pax.poslinkadmin.constant.TransactionType");
            Object saleType = Enum.valueOf((Class<Enum>) transactionTypeClass, "SALE");
            doCreditRequestClass.getMethod("setTransactionType", transactionTypeClass)
                    .invoke(request, saleType);

            Class<?> amountRequestClass = resolveClass("com.pax.poslinkadmin.util.AmountRequest");
            Object amountRequest = amountRequestClass.getDeclaredConstructor().newInstance();
            long cents = amount.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
            amountRequestClass.getMethod("setTransactionAmount", String.class)
                    .invoke(amountRequest, String.valueOf(cents));
            doCreditRequestClass.getMethod("setAmountInformation", amountRequestClass)
                    .invoke(request, amountRequest);

            Class<?> traceRequestClass = resolveClass("com.pax.poslinksemiintegration.util.TraceRequest");
            Object traceRequest = traceRequestClass.getDeclaredConstructor().newInstance();
            traceRequestClass.getMethod("setEcrReferenceNumber", String.class)
                    .invoke(traceRequest, invoiceRef);
            traceRequestClass.getMethod("setInvoiceNumber", String.class)
                    .invoke(traceRequest, deriveInvoiceNumber(invoiceRef));
            doCreditRequestClass.getMethod("setTraceInformation", traceRequestClass)
                    .invoke(request, traceRequest);

            Object executionResult = transaction.getClass()
                    .getMethod("doCredit", doCreditRequestClass)
                    .invoke(transaction, request);
            return mapSemiIntegrationResult(executionResult, invoiceRef);
        } catch (PaxTerminalException e) {
            throw e;
        } catch (Exception e) {
            throw new PaxTerminalException("PAX payment failed: " + rootCauseMessage(e), e);
        }
    }

    /**
     * POSLink 2 requires a concrete comm-setting subclass ({@code TcpSetting}, {@code UartSetting}, etc.)
     * annotated with {@code @CommSettingType}. The base {@code NetSetting} class lacks that annotation
     * and causes NPE inside {@code BaseTerminalImpl.init}.
     */
    private Object createSemiIntegrationCommSetting() throws Exception {
        String commType = settings.commType != null ? settings.commType.trim().toUpperCase() : "TCP";
        if ("UART".equals(commType) || "SERIAL".equals(commType) || commType.startsWith("COM")) {
            Class<?> uartSettingClass = resolveClass("com.pax.poscore.commsetting.UartSetting");
            Object uartSetting = uartSettingClass.getDeclaredConstructor().newInstance();
            uartSettingClass.getMethod("setSerialPort", String.class).invoke(uartSetting, commType);
            uartSettingClass.getMethod("setBaudRate", String.class).invoke(uartSetting, "9600");
            uartSettingClass.getMethod("setTimeout", int.class).invoke(uartSetting, settings.timeoutMs);
            return uartSetting;
        }

        Class<?> tcpSettingClass = resolveClass("com.pax.poscore.commsetting.TcpSetting");
        return tcpSettingClass.getConstructor(String.class, String.class, int.class)
                .newInstance(settings.host, String.valueOf(settings.port), settings.timeoutMs);
    }

    /**
     * {@code getTerminal()} returns null when the internal POSLink {@code init()} handshake fails.
     * Probe init directly so the UI shows the SDK error (e.g. RECV ACK ERROR) instead of a generic message.
     */
    private String describeTerminalConnectFailure(Object commSetting) {
        String base = "Could not connect to PAX terminal at " + settings.host + ":" + settings.port + ".";
        try {
            Class<?> terminalImplClass = Class.forName("com.pax.poslinksemiintegration.c", true, sdkClassLoader);
            var ctor = terminalImplClass.getDeclaredConstructor(
                    resolveClass("com.pax.poscore.commsetting.CommunicationSetting"));
            ctor.setAccessible(true);
            Object terminal = ctor.newInstance(commSetting);
            Object manage = terminal.getClass().getMethod("getManage").invoke(terminal);
            Object initResult = manage.getClass().getMethod("init").invoke(manage);
            boolean successful = (Boolean) initResult.getClass().getMethod("isSuccessful").invoke(initResult);
            String message = (String) initResult.getClass().getMethod("message").invoke(initResult);
            if (!successful && message != null && !message.isBlank()) {
                return base + " POSLink init failed: " + message
                        + ". Ensure BroadPOS simulator is in POSLink TCP server mode on port "
                        + settings.port + ".";
            }
        } catch (Exception e) {
            logger.debug("Could not probe POSLink init failure", e);
        }
        return base + " Ensure BroadPOS simulator is running with POSLink TCP enabled on port "
                + settings.port + " and Windows Firewall allows inbound connections.";
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null && !message.isBlank() ? message : cause.getClass().getSimpleName();
    }

    private PaxPaymentResult mapSemiIntegrationResult(Object executionResult, String invoiceRef)
            throws Exception {
        if (executionResult == null) {
            return PaxPaymentResult.declined("No response from PAX terminal", "NO_RESPONSE");
        }

        boolean successful = (Boolean) executionResult.getClass().getMethod("isSuccessful")
                .invoke(executionResult);
        String executionMessage = (String) executionResult.getClass().getMethod("message")
                .invoke(executionResult);
        if (!successful) {
            return PaxPaymentResult.declined(
                    executionMessage != null && !executionMessage.isBlank()
                            ? executionMessage
                            : "Terminal communication error.",
                    "ERROR");
        }

        Object response = executionResult.getClass().getMethod("response").invoke(executionResult);
        if (response == null) {
            return PaxPaymentResult.declined("Terminal returned no payment response", "NO_PAYMENT_RESPONSE");
        }

        String responseCode = (String) response.getClass().getMethod("responseCode").invoke(response);
        String responseMessage = (String) response.getClass().getMethod("responseMessage").invoke(response);
        boolean approved = responseCode != null && APPROVED_RESULT_CODE.equals(responseCode.trim());
        if (!approved) {
            return PaxPaymentResult.declined(
                    responseMessage != null && !responseMessage.isBlank()
                            ? responseMessage
                            : "Card payment declined",
                    responseCode != null ? responseCode : "DECLINED");
        }

        Object hostInformation = invokeNoArg(response, "hostInformation");
        Object accountInformation = invokeNoArg(response, "accountInformation");
        Object traceInformation = invokeNoArg(response, "traceInformation");

        String authCode = invokeString(hostInformation, "authorizationCode");
        String hostRef = invokeString(hostInformation, "hostReferenceNumber");
        String refNum = invokeString(traceInformation, "referenceNumber");
        String account = invokeString(accountInformation, "account");
        String lastFour = maskLastFour(account);
        Object cardTypeEnum = invokeNoArg(accountInformation, "cardType");
        String cardType = cardTypeEnum != null ? cardTypeEnum.toString() : null;
        Object entryModeEnum = invokeNoArg(accountInformation, "entryMode");
        String entryMode = entryModeEnum != null ? entryModeEnum.toString() : null;

        return PaxPaymentResult.approved(
                responseMessage != null && !responseMessage.isBlank() ? responseMessage : "Approved",
                authCode,
                refNum,
                lastFour,
                cardType,
                cardType,
                entryMode,
                hostRef,
                invoiceRef);
    }

    private String deriveInvoiceNumber(String invoiceRef) {
        String invNum = invoiceRef.replaceAll("\\D", "");
        if (invNum.isEmpty()) {
            invNum = String.valueOf(System.currentTimeMillis() % 1_000_000L);
        }
        if (invNum.length() > 12) {
            invNum = invNum.substring(invNum.length() - 12);
        }
        return invNum;
    }

    private String maskLastFour(String account) {
        if (account == null) {
            return null;
        }
        String digits = account.replaceAll("\\D", "");
        return digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String invokeString(Object target, String methodName) {
        Object value = invokeNoArg(target, methodName);
        return value != null ? value.toString() : null;
    }

    private void applyCommSetting(Object commSetting) throws Exception {
        // CommSetting in POSLink uses setters (setType/setDestIP/setDestPort/setTimeOut), not public fields.
        if (!invokeSetter(commSetting, settings.commType, "setType", "setCommType")) {
            setStringField(commSetting, "CommType", settings.commType);
        }
        if (!invokeSetter(commSetting, settings.host, "setDestIP", "setIP")) {
            setStringField(commSetting, "DestIP", settings.host);
        }
        if (!invokeSetter(commSetting, String.valueOf(settings.port), "setDestPort", "setPort")) {
            setStringField(commSetting, "DestPort", String.valueOf(settings.port));
        }
        if (!invokeSetter(commSetting, String.valueOf(settings.timeoutMs), "setTimeOut", "setTimeout")) {
            setStringField(commSetting, "TimeOut", String.valueOf(settings.timeoutMs));
        }
        // Serial port is irrelevant for TCP but some builds require it to be non-null.
        invokeSetter(commSetting, "", "setSerialPort");
    }

    private void setPaymentRequestFields(Object paymentRequest, BigDecimal amount, String invoiceRef)
            throws Exception {
        long cents = amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();

        // ParseTenderType/ParseTransType return the protocol code the terminal expects.
        String tender = parseRequestEnum(paymentRequest, "ParseTenderType", "CREDIT");
        String trans = parseRequestEnum(paymentRequest, "ParseTransType", "SALE");
        setStringField(paymentRequest, "TenderType", tender != null ? tender : "CREDIT");
        setStringField(paymentRequest, "TransType", trans != null ? trans : "SALE");

        setStringField(paymentRequest, "Amount", String.valueOf(cents));
        setStringField(paymentRequest, "ECRRefNum", invoiceRef);
        // InvNum must be numeric on many terminals; derive digits from the ref.
        setStringField(paymentRequest, "InvNum", deriveInvoiceNumber(invoiceRef));
    }

    private String parseRequestEnum(Object paymentRequest, String method, String value) {
        try {
            Method m = paymentRequest.getClass().getMethod(method, String.class);
            Object result = m.invoke(paymentRequest, value);
            return result != null ? result.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private PaxPaymentResult mapPaymentResponse(Object paymentResponse, String invoiceRef) {
        if (paymentResponse == null) {
            // OK comm code but no response payload — cannot confirm approval, so do NOT record a sale.
            return PaxPaymentResult.declined("Terminal returned no payment response", "NO_PAYMENT_RESPONSE");
        }

        String resultCode = readStringProperty(paymentResponse, "ResultCode");
        String resultTxt = readStringProperty(paymentResponse, "ResultTxt", "Message", "Message1");

        boolean approved = resultCode != null && APPROVED_RESULT_CODE.equals(resultCode.trim());
        if (!approved) {
            return PaxPaymentResult.declined(
                    resultTxt != null && !resultTxt.isBlank() ? resultTxt : "Card payment declined",
                    resultCode != null ? resultCode : "DECLINED");
        }

        String authCode = readStringProperty(paymentResponse, "AuthCode", "AuthorizationCode");
        String refNum = readStringProperty(paymentResponse, "RefNum", "HostReferenceNumber", "TransactionID");
        String lastFour = readStringProperty(paymentResponse, "BogusAccountNum", "MaskedPAN", "CardInfo");
        if (lastFour != null) {
            String digits = lastFour.replaceAll("\\D", "");
            lastFour = digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
        }
        String cardType = readStringProperty(paymentResponse, "CardType", "PaymentType");
        String entryMode = readStringProperty(paymentResponse, "EntryMode", "PLEntryMode");
        String hostRef = readStringProperty(paymentResponse, "HostCode", "HostReferenceNumber");

        return PaxPaymentResult.approved(
                resultTxt != null && !resultTxt.isBlank() ? resultTxt : "Approved",
                authCode,
                refNum,
                lastFour,
                cardType,
                cardType,
                entryMode,
                hostRef,
                invoiceRef);
    }

    /** Normalises ProcessTransResult.Code (an enum) into OK / TIMEOUT / CANCELED / ERROR. */
    private String readProcessResultCode(Object processResult) {
        Object code = readObject(processResult, "Code", "getCode");
        if (code == null) {
            code = readStringProperty(processResult, "Code", "ResultCode");
        }
        if (code == null) {
            return null;
        }
        String normalized = code.toString().trim().toUpperCase();
        if (normalized.contains("OK")) {
            return "OK";
        }
        if (normalized.contains("TIME")) {
            return "TIMEOUT";
        }
        if (normalized.contains("CANCEL")) {
            return "CANCELED";
        }
        return "ERROR";
    }

    private String describeCommFailure(String commCode, String detail) {
        switch (commCode != null ? commCode : "ERROR") {
            case "TIMEOUT":
                return "No response from terminal (timed out). Check the terminal and network.";
            case "CANCELED":
                return "Transaction cancelled at the terminal.";
            default:
                return detail != null && !detail.isBlank() ? detail : "Terminal communication error.";
        }
    }

    /** Reads an object-valued public field, falling back to no-arg getters. */
    private Object readObject(Object target, String fieldName, String... getterNames) {
        if (target == null) {
            return null;
        }
        try {
            Field field = target.getClass().getField(fieldName);
            Object value = field.get(target);
            if (value != null) {
                return value;
            }
        } catch (Exception ignored) {
            // try getters
        }
        for (String getter : getterNames) {
            try {
                Method method = target.getClass().getMethod(getter);
                Object value = method.invoke(target);
                if (value != null) {
                    return value;
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    /** Invokes the first matching 1-arg setter (case-insensitive), coercing the value to the param type. */
    private boolean invokeSetter(Object target, Object value, String... candidateNames) {
        for (String name : candidateNames) {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equalsIgnoreCase(name) && method.getParameterCount() == 1) {
                    try {
                        method.invoke(target, coerce(value, method.getParameterTypes()[0]));
                        return true;
                    } catch (Exception ignored) {
                        // try next candidate
                    }
                }
            }
        }
        return false;
    }

    private Object coerce(Object value, Class<?> paramType) {
        if (value == null) {
            return null;
        }
        if (paramType == int.class || paramType == Integer.class) {
            return Integer.parseInt(value.toString());
        }
        if (paramType == String.class) {
            return value.toString();
        }
        return value;
    }

    private void setFieldOrProperty(Object target, String name, Object value) throws Exception {
        try {
            Field field = target.getClass().getField(name);
            field.set(target, value);
            return;
        } catch (NoSuchFieldException ignored) {
            // try setter
        }
        if (invokeSetter(target, value, "set" + name)) {
            return;
        }
        throw new PaxTerminalException("Could not set " + name + " on " + target.getClass().getName());
    }

    private void setStringField(Object target, String fieldName, String value) throws Exception {
        try {
            Field field = target.getClass().getField(fieldName);
            Class<?> type = field.getType();
            if (type == int.class || type == Integer.class) {
                field.set(target, Integer.parseInt(value));
            } else {
                field.set(target, value);
            }
            return;
        } catch (NoSuchFieldException ignored) {
            // try setter
        }
        invokeSetter(target, value, "set" + fieldName);
    }

    private String readStringProperty(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            try {
                Field field = target.getClass().getField(name);
                Object value = field.get(target);
                if (value != null && !value.toString().isBlank()) {
                    return value.toString();
                }
            } catch (Exception ignored) {
                // try getter
            }
            try {
                Method getter = target.getClass().getMethod("get" + name);
                Object value = getter.invoke(target);
                if (value != null && !value.toString().isBlank()) {
                    return value.toString();
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private Class<?> tryResolveClass(String className) {
        try {
            return Class.forName(className, true, sdkClassLoader);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private Class<?> resolveClass(String className) throws PaxTerminalException {
        Class<?> resolved = tryResolveClass(className);
        if (resolved != null) {
            return resolved;
        }
        throw new PaxTerminalException(getSdkLoadHint());
    }

    private Class<?> resolveClass(String[] candidates) throws PaxTerminalException {
        for (String name : candidates) {
            Class<?> resolved = tryResolveClass(name);
            if (resolved != null) {
                return resolved;
            }
        }
        throw new PaxTerminalException(getSdkLoadHint());
    }

    private ClassLoader buildSdkClassLoader() throws PaxTerminalException {
        return createSdkClassLoader();
    }

    private static ClassLoader createSdkClassLoader() throws PaxTerminalException {
        List<URL> jarUrls = new ArrayList<>();
        Path libDir = resolveLibDir();
        if (Files.isDirectory(libDir)) {
            try (Stream<Path> files = Files.list(libDir)) {
                files.filter(p -> p.toString().endsWith(".jar"))
                        .forEach(p -> {
                            try {
                                jarUrls.add(p.toUri().toURL());
                            } catch (Exception e) {
                                logger.warn("Could not load PAX JAR {}", p, e);
                            }
                        });
            } catch (Exception e) {
                logger.warn("Could not scan lib/pax for SDK JARs", e);
            }
        }

        if (jarUrls.isEmpty()) {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            for (String name : POS_LINK_CLASS_NAMES) {
                try {
                    Class.forName(name, false, context);
                    return context;
                } catch (ClassNotFoundException ignored) {
                    // try classpath
                }
            }
            try {
                Class.forName(POS_LINK_SEMI_CLASS, false, context);
                return context;
            } catch (ClassNotFoundException ignored) {
                // fall through
            }
            throw new PaxTerminalException(getSdkLoadHint());
        }

        return new URLClassLoader(jarUrls.toArray(new URL[0]), PosLink2TerminalClient.class.getClassLoader());
    }

    private static Path resolveLibDir() {
        Path libDir = Paths.get("lib", "pax");
        if (!Files.isDirectory(libDir)) {
            libDir = Paths.get(System.getProperty("user.dir"), "lib", "pax");
        }
        return libDir;
    }

    private static List<String> listJarNames(Path libDir) {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(libDir)) {
            return names;
        }
        try (Stream<Path> files = Files.list(libDir)) {
            files.filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> p.getFileName().toString())
                    .forEach(names::add);
        } catch (Exception e) {
            logger.warn("Could not scan lib/pax for SDK JARs", e);
        }
        return names;
    }
}
