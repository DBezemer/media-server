package com.kaltura.media_server.modules;
/**
 * Created by ron.yadgar on 09/05/2016.
 */

import com.kaltura.client.KalturaApiException;
import com.kaltura.client.enums.KalturaEntryServerNodeType;
import com.kaltura.client.types.KalturaLiveEntry;
import com.wowza.wms.amf.AMFDataList;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.client.IClient;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.request.RequestFunction;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.MediaStreamActionNotifyBase;
import com.wowza.wms.stream.live.MediaStreamLive;
import com.wowza.wms.vhost.IVHost;
import com.wowza.wms.vhost.VHost;
import org.apache.log4j.Logger;
import com.wowza.wms.rtp.model.RTPSession;
import com.kaltura.media_server.services.*;

import java.util.HashMap;
import java.util.regex.Matcher;


public class AuthenticationModule extends ModuleBase  {
    private enum StreamType {
        UNKNOWN_STREAM_TYPE, RTMP, RTSP
    }
    private static final Logger logger = Logger.getLogger(AuthenticationModule.class);
    public static final String STREAM_ACTION_PROPERTY = "AuthenticatioStreamActionNotifier";
    private IVHost Ivhost;
    @SuppressWarnings("serial")
    public class ClientConnectException extends Exception{

        public ClientConnectException(String message) {
            super(message);
        }
    }

    public void onAppStart(final IApplicationInstance appInstance) {
        logger.info("Initiallizing " + appInstance.getName());
        Ivhost = appInstance.getVHost();
        KalturaEntryDataPersistence.setAppInstance(appInstance);
    }

    public void onConnect(IClient client, RequestFunction function, AMFDataList params) {
        WMSProperties properties = client.getProperties();
        String rtmpUrl = properties.getPropertyStr(Constants.CLIENT_PROPERTY_CONNECT_URL);
        String IP = client.getIp();
        logger.debug("Geting url: " + rtmpUrl+ " from client "+ IP);

        try {
            HashMap<String, String>  queryParameters = Utils.getRtmpUrlParameters(rtmpUrl, client.getQueryStr());
            onClientConnect(properties, queryParameters);
        } catch (Exception  e) {
            logger.error("Entry authentication failed with url [" + rtmpUrl + "]: " + e.getMessage());
            client.rejectConnection();
            sendClientOnStatusError((IClient)client, "NetStream.Play.Failed","Unable to authenticate url; [" + rtmpUrl + "]: " + e.getMessage());
            DiagnosticsProvider.addRejectedStreamFromClient(e.getMessage(), client);
        }
    }

    private void onClientConnect(WMSProperties properties, HashMap<String, String> requestParams) throws KalturaApiException, ClientConnectException, Exception {

        if (!requestParams.containsKey(Constants.REQUEST_PROPERTY_ENTRY_ID)){
            throw new ClientConnectException("Missing argument: entryId");
        }
        if (!requestParams.containsKey(Constants.REQUEST_PROPERTY_TOKEN)){
            throw new ClientConnectException("Missing argument: token");
        }
        if (!requestParams.containsKey(Constants.REQUEST_PROPERTY_PARTNER_ID)){
            throw new ClientConnectException("Missing argument: partnerId");
        }
        if (!requestParams.containsKey(Constants.REQUEST_PROPERTY_SERVER_INDEX)){
            throw new ClientConnectException("Missing argument: server index");
        }

        int partnerId = Integer.parseInt(requestParams.get(Constants.REQUEST_PROPERTY_PARTNER_ID));
        String entryId = requestParams.get(Constants.REQUEST_PROPERTY_ENTRY_ID);
        String propertyServerIndex = requestParams.get(Constants.REQUEST_PROPERTY_SERVER_INDEX);
        String token = requestParams.get(Constants.REQUEST_PROPERTY_TOKEN);
        KalturaEntryServerNodeType serverIndex = KalturaEntryServerNodeType.get(propertyServerIndex);

        synchronized (properties) {
            properties.setProperty(Constants.CLIENT_PROPERTY_SERVER_INDEX, propertyServerIndex);
            properties.setProperty(Constants.KALTURA_LIVE_ENTRY_ID, entryId);
        }
        authenticate(entryId, partnerId, token, serverIndex);
    }

    private void authenticate(String entryId, int partnerId, String token, KalturaEntryServerNodeType serverIndex) throws KalturaApiException, ClientConnectException, Exception {
        Object authenticationLock = KalturaEntryDataPersistence.getLock(entryId);
        synchronized (authenticationLock) {
            try {
                logger.debug("(" + entryId + ") Starting authentication process");
                if (Boolean.TRUE.equals(KalturaEntryDataPersistence.getPropertyByEntry(entryId, Constants.KALTURA_ENTRY_AUTHENTICATION_ERROR_FLAG))) {
                    throw new Exception("(" + entryId + ") Authentication Error Flag is up!");
                }
                long currentTime = System.currentTimeMillis();
                Object entryLastValidationTime = KalturaEntryDataPersistence.setProperty(entryId, Constants.KALTURA_ENTRY_VALIDATED_TIME, currentTime);

                if ((entryLastValidationTime == null) || (currentTime - (long)entryLastValidationTime > Constants.KALTURA_MIN_TIME_BETWEEN_AUTHENTICATIONS)) {
                    KalturaLiveEntry liveEntry = (KalturaLiveEntry) KalturaAPI.getKalturaAPI().authenticate(entryId, partnerId, token, serverIndex);
                    KalturaEntryDataPersistence.setProperty(entryId, Constants.CLIENT_PROPERTY_KALTURA_LIVE_ENTRY, liveEntry);
                    KalturaEntryDataPersistence.setProperty(entryId, Constants.KALTURA_ENTRY_AUTHENTICATION_ERROR_FLAG, false);
                    logger.info("(" + entryId + ") Entry authenticated successfully!");
                } else {
                    logger.debug("(" + entryId + ") Entry did not authenticate! Last authentication: [" + (currentTime - (long)entryLastValidationTime) + "] MS ago");
                }
            }
            catch (Exception e) {
                logger.error("(" + entryId + ") Exception was thrown during authentication process");
                KalturaEntryDataPersistence.setProperty(entryId, Constants.KALTURA_ENTRY_AUTHENTICATION_ERROR_FLAG, true);
                KalturaEntryDataPersistence.setProperty(entryId, Constants.KALTURA_ENTRY_VALIDATED_TIME, (long)0);
                throw e;
            }
        }
    }

    public void onDisconnect(IClient client) {
        try{
            String entryId = Utils.getEntryIdFromClient(client);
            logger.info("(" + entryId + ") Entry stopped");
            KalturaEntryDataPersistence.entriesMapCleanUp();
        }
        catch (Exception  e){
            logger.info("Error" + e.getMessage());
        }
    }

    public void onStreamCreate(IMediaStream stream) {
        LiveStreamListener  actionListener = new LiveStreamListener();
        logger.debug("onStreamCreate - [" + stream.getName() + "]");
        WMSProperties props = stream.getProperties();
        synchronized (props)
        {
            props.setProperty(STREAM_ACTION_PROPERTY, actionListener);
        }
        stream.addClientListener(actionListener);
    }

    public void onStreamDestroy(IMediaStream stream) {
        logger.debug("onStreamDestroy - [" + stream.getName() + "]");
        LiveStreamListener actionListener = null;
        WMSProperties props = stream.getProperties();
        synchronized (props)
        {
            actionListener = (LiveStreamListener) stream.getProperties().get(STREAM_ACTION_PROPERTY);
        }
        if (actionListener != null)
        {
            stream.removeClientListener(actionListener);
            logger.info("removeClientListener: " + stream.getSrc());
        }
    }

    public void onRTPSessionCreate(RTPSession rtpSession)
    {
        String queryStr = rtpSession.getQueryStr();
        String uriStr = rtpSession.getUri();
        try {
            logger.debug("onRTPSessionCreate - [" + rtpSession.getSessionId() + "]");
            HashMap<String, String>  queryParameters = Utils.getRtmpUrlParameters(uriStr, queryStr);
            onClientConnect(rtpSession.getProperties(), queryParameters);
        } catch (Exception  e) {
            logger.error("Entry authentication failed with url [" + uriStr + "]: " + e.getMessage());
            rtpSession.rejectSession();
            DiagnosticsProvider.addRejectedStreamFromRTSP(e.getMessage(), rtpSession);
        }
    }

    public void onRTPSessionDestroy(RTPSession rtpSession)
    {
        try{
            logger.debug("onRTPSessionCreate - [" + rtpSession.getSessionId() + "]");
            String entryId = Utils.getEntryIdFromRTPSession(rtpSession);
            logger.info("Entry removed [" + entryId + "]");
            KalturaEntryDataPersistence.entriesMapCleanUp();
        }
        catch (Exception  e){
            logger.info("Error" + e.getMessage());
        }
    }



    class LiveStreamListener extends  MediaStreamActionNotifyBase{

        private void shutdown(IMediaStream stream, String msg, StreamType streamType){

            logger.error(msg);
            IClient client = stream.getClient();

            switch(streamType) {
                case RTMP:
                    sendClientOnStatusError((IClient)client, "NetStream.Play.Failed", msg);
                    client.setShutdownClient(true);
                    DiagnosticsProvider.addRejectedStreamFromClient(msg, client);
                    break;
                case RTSP: // rtp
                    RTPSession rtpSession = stream.getRTPStream().getSession();
                    Ivhost.getRTPContext().shutdownRTPSession(rtpSession);
                    DiagnosticsProvider.addRejectedStreamFromRTSP(msg, rtpSession);
                    break;
                default:

            }

        }

        public void onPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend) {
            if (stream.isTranscodeResult()){
                return;
            }
            StreamType streamType = StreamType.UNKNOWN_STREAM_TYPE;
            try {
                String entryByClient;
                if (stream.getClient() != null) {
                    IClient client = stream.getClient();
                    entryByClient = Utils.getEntryIdFromClient(client);
                    streamType = StreamType.RTMP;
                }
                else if (stream.getRTPStream() != null && stream.getRTPStream().getSession() !=null) {
                    RTPSession rtpSession = stream.getRTPStream().getSession();
                    entryByClient = Utils.getEntryIdFromRTPSession(rtpSession);
                    streamType = StreamType.RTSP;
                } else {
                    // Lilach todo: check if there's a way to shutdown unknown stream!!!
                    logger.error("Fatal Error! Client does not exist");
                    return;
                }
                Matcher matcher = Utils.getStreamNameMatches(streamName);
                if (matcher == null) {
                    String msg = "Published stream is invalid [" + streamName + "]";
                    shutdown(stream, msg, streamType);
                    return;
                }
                String entryByStream = matcher.group(1);
                String flavor = matcher.group(2);


                if (!entryByStream.equals(entryByClient)) {
                    String msg = "Published  stream name [" + streamName + "] does not match entry id [" + entryByClient  + "]";
                    shutdown(stream, msg, streamType);
                    return;
                }
                if (!Utils.isNumeric(flavor)) {
                    String msg = "Published  stream name [" + streamName + "], has wrong suffix stream name: " + flavor;
                    shutdown(stream, msg, streamType);
                    return;
                }

            }
            catch (Exception  e) {
                logger.error("Exception in onPublish: ", e);
            }
        }
    }
}
