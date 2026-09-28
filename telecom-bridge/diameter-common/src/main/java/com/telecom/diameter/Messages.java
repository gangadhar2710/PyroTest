package com.telecom.diameter;

import static com.telecom.diameter.DiameterConstants.*;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Builders for the Diameter messages used by the gateway and the simulator. */
public final class Messages {
    private Messages() {}

    private static final String PRODUCT = "telecom-bridge";

    // ---------------- Base protocol ----------------

    public static DiameterMessage cer(String host, String realm, InetAddress ip, int hbh, int e2e) {
        return new DiameterMessage(FLAG_REQUEST, CMD_CER, APP_COMMON, hbh, e2e, List.of(
                Avp.utf8(AVP_ORIGIN_HOST, host),
                Avp.utf8(AVP_ORIGIN_REALM, realm),
                Avp.address(AVP_HOST_IP_ADDRESS, ip),
                Avp.unsigned32(AVP_VENDOR_ID, 0),
                Avp.utf8(AVP_PRODUCT_NAME, PRODUCT).withMandatory(false),
                Avp.unsigned32(AVP_AUTH_APPLICATION_ID, APP_CREDIT_CONTROL)));
    }

    public static DiameterMessage cea(DiameterMessage cer, String host, String realm, InetAddress ip, long result) {
        return new DiameterMessage(0, CMD_CER, APP_COMMON, cer.hopByHop(), cer.endToEnd(), List.of(
                Avp.unsigned32(AVP_RESULT_CODE, result),
                Avp.utf8(AVP_ORIGIN_HOST, host),
                Avp.utf8(AVP_ORIGIN_REALM, realm),
                Avp.address(AVP_HOST_IP_ADDRESS, ip),
                Avp.unsigned32(AVP_VENDOR_ID, 0),
                Avp.utf8(AVP_PRODUCT_NAME, PRODUCT).withMandatory(false),
                Avp.unsigned32(AVP_AUTH_APPLICATION_ID, APP_CREDIT_CONTROL)));
    }

    public static DiameterMessage dwr(String host, String realm, int hbh, int e2e) {
        return new DiameterMessage(FLAG_REQUEST, CMD_DWR, APP_COMMON, hbh, e2e, List.of(
                Avp.utf8(AVP_ORIGIN_HOST, host),
                Avp.utf8(AVP_ORIGIN_REALM, realm)));
    }

    public static DiameterMessage dwa(DiameterMessage dwr, String host, String realm) {
        return new DiameterMessage(0, CMD_DWR, APP_COMMON, dwr.hopByHop(), dwr.endToEnd(), List.of(
                Avp.unsigned32(AVP_RESULT_CODE, RESULT_SUCCESS),
                Avp.utf8(AVP_ORIGIN_HOST, host),
                Avp.utf8(AVP_ORIGIN_REALM, realm)));
    }

    // ---------------- Credit Control (Ro/Gy, RFC 4006) ----------------

    public static DiameterMessage ccr(String sessionId, String host, String realm, String destRealm,
                                      String serviceContextId, String msisdn, long requestedOctets,
                                      int requestNumber, int hbh, int e2e) {
        List<Avp> avps = new ArrayList<>();
        avps.add(Avp.utf8(AVP_SESSION_ID, sessionId));                       // Session-Id MUST come first
        avps.add(Avp.utf8(AVP_ORIGIN_HOST, host));
        avps.add(Avp.utf8(AVP_ORIGIN_REALM, realm));
        avps.add(Avp.utf8(AVP_DEST_REALM, destRealm));
        avps.add(Avp.unsigned32(AVP_AUTH_APPLICATION_ID, APP_CREDIT_CONTROL));
        avps.add(Avp.utf8(AVP_SERVICE_CONTEXT_ID, serviceContextId));
        avps.add(Avp.unsigned32(AVP_CC_REQUEST_TYPE, CC_REQUEST_TYPE_INITIAL));
        avps.add(Avp.unsigned32(AVP_CC_REQUEST_NUMBER, requestNumber));
        avps.add(Avp.time(AVP_EVENT_TIMESTAMP, System.currentTimeMillis() / 1000));
        avps.add(Avp.grouped(AVP_SUBSCRIPTION_ID,
                Avp.unsigned32(AVP_SUBSCRIPTION_ID_TYPE, SUBSCRIPTION_ID_TYPE_E164),
                Avp.utf8(AVP_SUBSCRIPTION_ID_DATA, msisdn)));
        avps.add(Avp.grouped(AVP_MULTIPLE_SERVICES_CC,
                Avp.grouped(AVP_REQUESTED_SERVICE_UNIT,
                        Avp.unsigned64(AVP_CC_TOTAL_OCTETS, requestedOctets))));
        return new DiameterMessage(FLAG_REQUEST | FLAG_PROXIABLE, CMD_CCR, APP_CREDIT_CONTROL, hbh, e2e, avps);
    }

    public static DiameterMessage cca(DiameterMessage ccr, String host, String realm,
                                      long result, long grantedOctets) {
        List<Avp> avps = new ArrayList<>();
        ccr.find(AVP_SESSION_ID).ifPresent(avps::add);
        avps.add(Avp.unsigned32(AVP_RESULT_CODE, result));
        avps.add(Avp.utf8(AVP_ORIGIN_HOST, host));
        avps.add(Avp.utf8(AVP_ORIGIN_REALM, realm));
        avps.add(Avp.unsigned32(AVP_AUTH_APPLICATION_ID, APP_CREDIT_CONTROL));
        ccr.find(AVP_CC_REQUEST_TYPE).ifPresent(avps::add);
        ccr.find(AVP_CC_REQUEST_NUMBER).ifPresent(avps::add);
        if (grantedOctets > 0) {
            avps.add(Avp.grouped(AVP_MULTIPLE_SERVICES_CC,
                    Avp.grouped(AVP_GRANTED_SERVICE_UNIT,
                            Avp.unsigned64(AVP_CC_TOTAL_OCTETS, grantedOctets))));
        }
        return new DiameterMessage(FLAG_PROXIABLE, CMD_CCR, APP_CREDIT_CONTROL, ccr.hopByHop(), ccr.endToEnd(), avps);
    }

    /** Extracts MSCC / Granted-Service-Unit / CC-Total-Octets from a CCA, or 0 when absent. */
    public static long grantedOctets(DiameterMessage cca) {
        return cca.find(AVP_MULTIPLE_SERVICES_CC)
                .flatMap(m -> Avp.find(m.asGrouped(), AVP_GRANTED_SERVICE_UNIT))
                .flatMap(g -> Avp.find(g.asGrouped(), AVP_CC_TOTAL_OCTETS))
                .map(Avp::asUnsigned64)
                .orElse(0L);
    }

    public static String sessionId(DiameterMessage msg) {
        return msg.find(AVP_SESSION_ID).map(Avp::asUtf8).orElse(null);
    }
}
