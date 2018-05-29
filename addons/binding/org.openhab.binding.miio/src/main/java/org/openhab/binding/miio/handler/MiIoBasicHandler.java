/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.miio.handler;

import static org.openhab.binding.miio.MiIoBindingConstants.CHANNEL_COMMAND;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.miio.MiIoBindingConstants;
import org.openhab.binding.miio.internal.MiIoCommand;
import org.openhab.binding.miio.internal.MiIoCryptoException;
import org.openhab.binding.miio.internal.MiIoSendCommand;
import org.openhab.binding.miio.internal.Utils;
import org.openhab.binding.miio.internal.basic.CommandParameterType;
import org.openhab.binding.miio.internal.basic.MiIoBasicChannel;
import org.openhab.binding.miio.internal.basic.MiIoBasicDevice;
import org.openhab.binding.miio.internal.basic.MiIoDeviceAction;
import org.openhab.binding.miio.internal.transport.MiIoAsyncCommunication;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link MiIoBasicHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Marcel Verpaalen - Initial contribution
 */
public class MiIoBasicHandler extends MiIoAbstractHandler {
    private static final int MAX_QUEUE = 5;
    private final Logger logger = LoggerFactory.getLogger(MiIoBasicHandler.class);
    private boolean hasChannelStructure;
    MiIoAsyncCommunication miioAsyncCom;

    List<MiIoBasicChannel> refreshList = new ArrayList<MiIoBasicChannel>();

    MiIoBasicDevice miioDevice;
    private Map<String, MiIoDeviceAction> actions;

    @NonNullByDefault
    public MiIoBasicHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        super.initialize();
        hasChannelStructure = false;
    }

    @Override
    public void dispose() {
        logger.debug("Disposing Xiaomi Mi IO Basic handler '{}'", getThing().getUID());
        if (pollingJob != null) {
            pollingJob.cancel(true);
            pollingJob = null;
        }
        if (miioCom != null) {
            lastId = miioCom.getId();
            miioCom.close();
            miioCom = null;
        }
        try {
            miioAsyncCom.close();
            miioAsyncCom = null;
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            logger.debug("Refreshing {}", channelUID);
            // updateData();
            return;
        }
        if (channelUID.getId().equals(CHANNEL_COMMAND)) {
            sendAsyncCommand(command.toString());
            // updateState(CHANNEL_COMMAND, new StringType(sendCommand(command.toString())));
        }
        // TODO cleanup debug stuff & add handling types
        logger.debug("Locating action for channel {}:{}", channelUID.getId(), command);
        if (actions != null) {
            if (actions.containsKey(channelUID.getId())) {
                String cmd = actions.get(channelUID.getId()).getCommand();
                CommandParameterType paramType = actions.get(channelUID.getId()).getparameterType();
                if (command instanceof OnOffType) {
                    if (paramType == CommandParameterType.ONOFF) {
                        cmd = cmd + "[\"" + command.toString().toLowerCase() + "\"]";
                    } else {
                        cmd = cmd + "[]";
                    }
                }
                if (command instanceof StringType) {
                    cmd = cmd + "[\"" + command.toString() + "\"]";
                }
                if (command instanceof DecimalType) {
                    cmd = cmd + "[" + command.toString().toLowerCase() + "]";
                }
                logger.debug(" sending command {}", cmd);
                sendAsyncCommand(cmd);
            } else {
                logger.debug("Channel Id {} not in mapping. Available:", channelUID.getId());
                for (String a : actions.keySet()) {
                    logger.debug("entries: {} : {}", a, actions.get(a));
                }
            }
        } else {
            logger.debug("Actions not loaded yet");
        }
    }

    @Override
    protected synchronized void updateData() {
        if (miioAsyncCom.getQueueLenght() > MAX_QUEUE) {
            logger.debug("No periodic update for '{}'. {} elements in queue ", getThing().getUID().toString(),
                    miioAsyncCom.getQueueLenght());
            return;
        } else {
            logger.debug("Periodic update for '{}'", getThing().getUID().toString());
        }

        checkChannelStructure();
        try {
            miioAsyncCom.sendPing(configuration.host);
        } catch (Exception e) {
            // ignore
        }
        try {
            if (!isIdentified) {
                miioAsyncCom.queueCommand(MiIoCommand.MIIO_INFO);
            }
            if (miioDevice != null) {
                refreshProperties(miioDevice);
            }
        } catch (Exception e) {
            logger.debug("Error while updating '{}'", getThing().getUID().toString(), e);
        }
    }

    private boolean refreshProperties(MiIoBasicDevice device) {
        // TODO horribly inefficient refresh with each time creation of the list etc.. for testing only
        // build list of properties to be refreshed, do not refresh for unlinked channels
        JsonArray getPropString = new JsonArray();
        refreshList = new ArrayList<MiIoBasicChannel>();
        for (MiIoBasicChannel miChannel : device.getDevice().getChannels()) {
            if (miChannel.getRefresh()) {
                refreshList.add(miChannel);
                getPropString.add(miChannel.getProperty());
            }
        }

        miioAsyncCom.registerListener(this); // this should not be needed

        // get the data based on the datatype
        try {
            miioAsyncCom.queueCommand(MiIoCommand.GET_PROPERTY, getPropString.toString());
        } catch (MiIoCryptoException | IOException e) {
            logger.debug("Send refresh failed {}", e.getMessage(), e);
        }
        return true;
    }

    @Override
    protected boolean initializeData() {
        miioAsyncCom = new MiIoAsyncCommunication(configuration.host, token,
                Utils.hexStringToByteArray(configuration.deviceId), lastId);
        miioAsyncCom.registerListener(this);
        try {
            miioAsyncCom.sendPing(configuration.host);
        } catch (Exception e) {
            logger.debug("ping {} failed", configuration.host);
        }
        return true;
    }

    private void checkDeviceType() {
        if (!isIdentified) {
            defineDeviceType();
        }
    }

    /**
     * Checks if the channel structure has been build already based on the model data. If not build it.
     */
    private void checkChannelStructure() {
        if (!hasChannelStructure) {
            if (configuration.model == null || configuration.model.isEmpty()) {
                logger.debug("Model needs to be determined");
            } else {
                hasChannelStructure = buildChannelStructure(configuration.model);
            }
        }
    }

    private void sendAsyncCommand(String command) {

        try {
            command = command.trim();
            String param = "";
            int loc = command.indexOf("[");
            loc = (loc > 0 ? loc : command.indexOf("{"));
            if (loc > 0) {
                param = command.substring(loc).trim();
                command = command.substring(0, loc).trim();
            }
            miioAsyncCom.queueCommand(command, param);
        } catch (MiIoCryptoException | IOException e) {
            disconnected(e.getMessage());
        }
    }

    private boolean buildChannelStructure(String deviceName) {
        // TODO This still needs significant cleanup but should be functional.
        // TODO If the model can't be found by the filename, load the other files and check for the id's
        logger.debug("Building Channel Structure for {} - Model: {}", getThing().getUID().toString(), deviceName);
        URL fn;
        try {
            Bundle bundle = bundleContext.getBundle();
            fn = bundle.getEntry(MiIoBindingConstants.DATABASE_PATH + deviceName + ".json");
            if (fn == null) {
                logger.warn("Database entry for model '{}' cannot be found.", deviceName);
                return false;
            } else {
                logger.debug("bundle: {}, {}, {}", bundle, fn.getFile());
            }
        } catch (Exception e) {
            logger.warn("Database entry for model '{}' cannot be found.", deviceName);
            return false;
        }
        try {
            JsonObject deviceMapping = Utils.convertFileToJSON(fn);
            // TODO Change to Trace later onwards
            logger.debug("Device Mapper: {}, {}, {}", fn.getFile(), deviceMapping.toString());

            Gson gson = new GsonBuilder().serializeNulls().create();
            miioDevice = gson.fromJson(deviceMapping, MiIoBasicDevice.class);

            for (Channel ch : getThing().getChannels()) {
                logger.debug("Current thing channels {}, type: {}", ch.getUID(), ch.getChannelTypeUID());
            }
            ThingBuilder thingBuilder = editThing();
            int channelsAdded = 0;

            // make a map of the actions
            actions = new HashMap<String, MiIoDeviceAction>();

            for (MiIoBasicChannel miChannel : miioDevice.getDevice().getChannels()) {
                logger.debug("properties {}", miChannel);
                for (MiIoDeviceAction action : miChannel.getActions()) {
                    actions.put(miChannel.getChannel(), action);
                }
                if (miChannel.getType() != null) {
                    channelsAdded += addChannel(thingBuilder, miChannel.getChannel(), miChannel.getChannelType(),
                            miChannel.getType(), miChannel.getFriendlyName()) ? 1 : 0;
                }
            }
            // only update if channels were added/removed
            if (channelsAdded > 0) {
                logger.debug("Current thing channels added: {}", channelsAdded);
                updateThing(thingBuilder.build());
            }
            return true;
        } catch (JsonIOException e) {
            logger.warn("Error reading database Json", e);
        } catch (JsonSyntaxException e) {
            logger.warn("Error reading database Json", e);
        } catch (IOException e) {
            logger.warn("Error reading database file", e);
        } catch (NullPointerException e) {
            logger.warn("Error creating channel structure", e);
        } catch (Exception e) {
            logger.warn("Error creating channel structure", e);
        }
        return false;
    }

    private boolean addChannel(ThingBuilder thingBuilder, String channel, String channelType, String datatype,
            String friendlyName) {
        if (channel == null || channel.isEmpty() || datatype == null || datatype.isEmpty()) {
            logger.info("Channel '{}' cannot be added incorrectly configured database. ", channel, getThing().getUID());
            return false;
        }
        ChannelUID channelUID = new ChannelUID(getThing().getUID(), channel);
        ChannelTypeUID channelTypeUID = new ChannelTypeUID(MiIoBindingConstants.BINDING_ID, channelType);

        // TODO only for testing. This should not be done finally. Channel only to be added when not there
        // already
        if (getThing().getChannel(channel) != null) {
            logger.info("Channel '{}' for thing {} already exist... removing", channel, getThing().getUID());
            thingBuilder.withoutChannel(new ChannelUID(getThing().getUID(), channel));
        }

        Channel newChannel = ChannelBuilder.create(channelUID, datatype).withType(channelTypeUID)
                .withLabel(friendlyName).build();
        thingBuilder.withChannel(newChannel);
        return true;
    }

    @Override
    public void onMessageReceived(MiIoSendCommand response) {
        super.onMessageReceived(response);
        // logger.debug("Handler received response type: {}, result: {}, fullresponse: {}", response.getCommand(),
        // response.getResult(), response.getResponse());
        // if (response.isError()) {
        // logger.debug("Error received: {}", response.getResponse().get("error"));
        // return;
        // }
        try {
            switch (response.getCommand()) {
                case MIIO_INFO:
                    // if (!isIdentified) {
                    // defineDeviceType(getJsonResultHelper(response.getResponse().toString()));
                    // }
                    // updateNetwork(response.getResult().getAsJsonObject());
                    break;
                case GET_PROPERTY:
                    if (response.getResult().isJsonArray()) {
                        JsonArray res = response.getResult().getAsJsonArray();
                        // update the states
                        for (int i = 0; i < refreshList.size(); i++) {
                            try {
                                if (refreshList.get(i).getType().equals("Number")) {
                                    updateState(refreshList.get(i).getChannel(),
                                            new DecimalType(res.get(i).getAsBigDecimal()));
                                }
                                if (refreshList.get(i).getType().equals("String")) {
                                    updateState(refreshList.get(i).getChannel(),
                                            new StringType(res.get(i).getAsString()));
                                }
                                if (refreshList.get(i).getType().equals("Switch")) {
                                    updateState(refreshList.get(i).getChannel(),
                                            res.get(i).getAsString().toLowerCase().equals("on") ? OnOffType.ON
                                                    : OnOffType.OFF);
                                }
                            } catch (Exception e) {
                                logger.debug("Error updating propery {} with '{}' : {}",
                                        refreshList.get(i).getChannel(), res.get(i).getAsString(), e.getMessage());
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            logger.debug("Error while handing message {}", response.getResponse(), e);
        }
    }
}
