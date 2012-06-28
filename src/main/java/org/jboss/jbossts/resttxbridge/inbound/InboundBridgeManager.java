package org.jboss.jbossts.resttxbridge.inbound;

import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.jboss.jbossts.star.util.TxSupport;
import org.jboss.logging.Logger;
import org.jboss.resteasy.util.Base64;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.jta.xa.XATxConverter;

/**
 * 
 * @author Gytis Trikleris
 * 
 */
public final class InboundBridgeManager {

    private static final Logger LOG = Logger.getLogger(InboundBridgeManager.class);

    /**
     * Mappings of rest transactions and inbound bridges. Map key is URL of the transaction.
     */
    private static final ConcurrentMap<String, InboundBridge> inboundBridgeMappings = new ConcurrentHashMap<String, InboundBridge>();

    /**
     * Mappings of participants and transactions. Map key is participant id and value is transaction URL.
     * 
     * This mapping is required when transaction coordinator is communicating with participant. When request to the participant
     * is made only participant id is received, therefore we need to get transaction URL.
     */
    private static final ConcurrentMap<String, String> participantMappings = new ConcurrentHashMap<String, String>();

    public static boolean addInboundBridge(InboundBridge inboundBridge) {
        /*
         * TODO if rest transaction is associated with new bridge (new request within same transaction was made
         * immediately after recovery) - notify rest-at coordinator about new participant url
         */
        
        InboundBridge mappedInboundBridge = inboundBridgeMappings.get(inboundBridge.getTxUrl());
        
        if (inboundBridge.equals(mappedInboundBridge)) {
            // Bridge is already mapped
            return true;
        }
        
        if (mappedInboundBridge != null) {
            // REST-AT trasnaction is already mapped with another bridge
            return false;
        }
        
        if (participantMappings.containsKey(inboundBridge.getParticipantId())) {
            // Participant is already mapped with another REST-AT transaction
            return false;
        }
        
        inboundBridgeMappings.put(inboundBridge.getTxUrl(), inboundBridge);
        participantMappings.put(inboundBridge.getParticipantId(), inboundBridge.getTxUrl());
        
        return true;
    }
    
    /**
     * 
     * @param txUrl
     * @param baseUrl
     * @return
     * @throws IllegalArgumentException
     * @throws XAException
     * @throws SystemException
     * @throws RollbackException 
     * @throws IllegalStateException 
     */
    public static InboundBridge getInboundBridge(String txUrl, String baseUrl) throws XAException, SystemException, IllegalStateException, RollbackException {
        System.out.println("InboundBridgeManager.getInboundBridge(txUrl=" + txUrl + ")");

        if (txUrl == null) {
            throw new IllegalArgumentException("Transaction URL is required");
        }

        if (baseUrl == null) {
            throw new IllegalArgumentException("Base URL is required");
        }

        if (!inboundBridgeMappings.containsKey(txUrl)) {
            createInboundBridgeMapping(txUrl, baseUrl);
        }

        return inboundBridgeMappings.get(txUrl);
    }

    /**
     * 
     * @param participantId
     * @return
     */
    public static synchronized String getParticipantTransaction(String participantId) {
        System.out.println("InboundBridgeManager.getParticipantTransaction(participantId=" + participantId + ")");

        if (participantId == null) {
            throw new IllegalArgumentException("Participant ID is required");
        }

        return participantMappings.get(participantId);
    }

    /**
     * 
     * @param txUrl
     * @throws SystemException
     */
    public static synchronized void removeInboundBridgeMapping(String txUrl) throws SystemException {
        System.out.println("InboundBridgeManager.removeInboundBridgeMapping(txUrl=" + txUrl + ")");

        if (txUrl == null) {
            throw new IllegalArgumentException("Transaction URL is required");
        }

        inboundBridgeMappings.get(txUrl).stop();
        inboundBridgeMappings.remove(txUrl);
    }

    /**
     * 
     * @param participantId
     * @throws SystemException
     */
    public static synchronized void removeParticipantMapping(String participantId) throws SystemException {
        System.out.println("InboundBridgeManager.removeParticipantMapping(participantId=" + participantId + ")");

        if (participantId == null) {
            throw new IllegalArgumentException("Participant ID is required");
        }

        String txUrl = participantMappings.get(participantId);
        removeInboundBridgeMapping(txUrl);
        participantMappings.remove(participantId);
    }

    /**
     * 
     * @param txUrl
     * @param baseUrl
     * @throws XAException
     * @throws SystemException
     * @throws RollbackException 
     * @throws IllegalStateException 
     */
    private static synchronized void createInboundBridgeMapping(String txUrl, String baseUrl) throws XAException,
            SystemException, IllegalStateException, RollbackException {

        System.out.println("InboundBridgeManager.createMapping(txUrl=" + txUrl + ")");

        if (inboundBridgeMappings.containsKey(txUrl)) {
            return;
        }

        // Xid for driving the subordinate transaction
        Xid xid = XATxConverter.getXid(new Uid(), false, BridgeDurableParticipant.XARESOURCE_FORMAT_ID);

        // construct the participantId in such as way as we can recognize it at recovery time
        String participantId = BridgeDurableParticipant.TYPE_IDENTIFIER + new Uid().fileStringForm();

        enlistParticipant(txUrl, participantId, baseUrl);
        InboundBridge bridge = new InboundBridge(xid, txUrl, participantId);
        inboundBridgeMappings.put(txUrl, bridge);
    }

    /**
     * Enlists participant with REST-AT transaction.
     * 
     * @param txUrl
     * @param participantId
     * @param baseUrl
     * @return URL to recover participant
     * @throws UnsupportedEncodingException 
     */
    private static synchronized void enlistParticipant(String txUrl, String participantId, String baseUrl) {
        System.out.println("InboundBridgeManager.enlistParticipant()");

        if (!baseUrl.substring(baseUrl.length() - 1).equals("/")) {
            baseUrl = baseUrl + "/";
        }

        String participantUrl = baseUrl + BridgeDurableParticipant.PARTICIPANT_SEGMENT + "/" + participantId;

        System.out.println("Participant url: " + participantUrl);

        String pUrls = TxSupport.getParticipantUrls(participantUrl, participantUrl);
        new TxSupport().httpRequest(new int[] { HttpURLConnection.HTTP_CREATED }, txUrl, "POST", TxSupport.POST_MEDIA_TYPE,
                pUrls, null);
        
        participantMappings.put(participantId, txUrl);
    }

}
