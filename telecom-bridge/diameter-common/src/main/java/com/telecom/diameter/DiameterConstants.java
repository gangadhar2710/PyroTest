package com.telecom.diameter;

/** Diameter constants from RFC 6733 (base) and RFC 4006 (Credit Control). */
public final class DiameterConstants {
    private DiameterConstants() {}

    // Header flags
    public static final int FLAG_REQUEST = 0x80;
    public static final int FLAG_PROXIABLE = 0x40;
    public static final int FLAG_ERROR = 0x20;

    // Command codes
    public static final int CMD_CER = 257;   // Capabilities-Exchange
    public static final int CMD_DWR = 280;   // Device-Watchdog
    public static final int CMD_DPR = 282;   // Disconnect-Peer
    public static final int CMD_CCR = 272;   // Credit-Control

    // Application ids
    public static final int APP_COMMON = 0;
    public static final int APP_CREDIT_CONTROL = 4;

    // AVP codes (base)
    public static final int AVP_SESSION_ID = 263;
    public static final int AVP_ORIGIN_HOST = 264;
    public static final int AVP_ORIGIN_REALM = 296;
    public static final int AVP_DEST_REALM = 283;
    public static final int AVP_HOST_IP_ADDRESS = 257;
    public static final int AVP_VENDOR_ID = 266;
    public static final int AVP_PRODUCT_NAME = 269;
    public static final int AVP_AUTH_APPLICATION_ID = 258;
    public static final int AVP_RESULT_CODE = 268;
    public static final int AVP_EVENT_TIMESTAMP = 55;

    // AVP codes (RFC 4006)
    public static final int AVP_CC_REQUEST_NUMBER = 415;
    public static final int AVP_CC_REQUEST_TYPE = 416;
    public static final int AVP_CC_TOTAL_OCTETS = 421;
    public static final int AVP_GRANTED_SERVICE_UNIT = 431;
    public static final int AVP_REQUESTED_SERVICE_UNIT = 437;
    public static final int AVP_SUBSCRIPTION_ID = 443;
    public static final int AVP_SUBSCRIPTION_ID_DATA = 444;
    public static final int AVP_SUBSCRIPTION_ID_TYPE = 450;
    public static final int AVP_MULTIPLE_SERVICES_CC = 456;
    public static final int AVP_SERVICE_CONTEXT_ID = 461;

    // Enumerations
    public static final int CC_REQUEST_TYPE_INITIAL = 1;
    public static final int SUBSCRIPTION_ID_TYPE_E164 = 0; // END_USER_E164 (MSISDN)

    // Result codes
    public static final long RESULT_SUCCESS = 2001;
    public static final long RESULT_UNABLE_TO_COMPLY = 5012;
}
