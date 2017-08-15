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

import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.miio.MiIoBindingConstants;
import org.openhab.binding.miio.internal.MiIoCommand;
import org.openhab.binding.miio.internal.Utils;
import org.openhab.binding.miio.internal.basic.CommandParameterType;
import org.openhab.binding.miio.internal.basic.MiIoBasicChannel;
import org.openhab.binding.miio.internal.basic.MiIoBasicDevice;
import org.openhab.binding.miio.internal.basic.MiIoDeviceAction;
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
    private final Logger logger = LoggerFactory.getLogger(MiIoBasicHandler.class);
    private boolean hasChannelStructure;

    MiIoBasicDevice miioDevice;
    private Map<String, MiIoDeviceAction> actions;

    public MiIoBasicHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        super.initialize();
        hasChannelStructure = false;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            logger.debug("Refreshing {}", channelUID);
            updateData();
            return;
        }
        if (channelUID.getId().equals(CHANNEL_COMMAND)) {
            updateState(CHANNEL_COMMAND, new StringType(sendCommand(command.toString())));
        }
        // TODO: cleanup debug stuff & add handling types
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
                sendCommand(cmd);
            } else {
                logger.debug("Channel Id {} not in mapping. Available:", channelUID.getId());
                for (String a : actions.keySet()) {
                    logger.debug("entries: {} : {}", a, actions.get(a));
                }

            }

        } else {
            logger.debug("Actions not leaded yet");
        }
    }

    @Override
    protected synchronized void updateData() {
        logger.debug("Update connection '{}'", getThing().getUID().toString());
        checkChannelStructure();
        if (!hasConnection()) {
            return;
        }
        try {
            int updatesSuccess = 0;
            if (updateNetwork()) {
                updatesSuccess += 1;
                if (!isIdentified) {
                    isIdentified = updateThingType(getJsonResultHelper(network.getValue()));
                }
            }
            if (miioDevice != null) {
                updatesSuccess += refreshProperties(miioDevice) ? 1 : 0;
            }
            if (updatesSuccess > 0) {
                updateStatus(ThingStatus.ONLINE);
            } else {
                disconnectedNoResponse();
            }
        } catch (Exception e) {
            logger.debug("Error while updating '{}'", getThing().getUID().toString(), e);
        }

    }

    private boolean refreshProperties(MiIoBasicDevice miioDevice2miioDevice) {
        // TODO: horribly inefficient refresh with each time creation of the list etc.. for testing only
        // build list of properties to be refreshed, do not refresh for unlinked channels
        JsonArray getPropString = new JsonArray();
        List<MiIoBasicChannel> refreshList = new ArrayList<MiIoBasicChannel>();
        for (MiIoBasicChannel miChannels : miioDevice.getDevice().getChannels()) {
            if (miChannels.getRefresh()) {
                refreshList.add(miChannels);
                getPropString.add(miChannels.getProperty());
            }
        }
        // get the data based on the datatype
        String reply = null;
        reply = sendCommand(MiIoCommand.GET_PROPERTY, getPropString.toString());
        // mock data for testing
        // if (reply == null) {
        // reply = "{\"result\":[\"off\",\"idle\",59,16,10,\"on\",\"on\",\"off\",322,22],\"id\":14}";
        // logger.debug("No Reply using for testing mocked reply: {}", reply);
        // }

        JsonArray res = ((JsonObject) parser.parse(reply)).get("result").getAsJsonArray();

        // update the states
        for (int i = 0; i < refreshList.size(); i++) {
            if (refreshList.get(i).getType().equals("Number")) {
                updateState(refreshList.get(i).getChannel(), new DecimalType(res.get(i).getAsBigDecimal()));
            }
            if (refreshList.get(i).getType().equals("String")) {
                updateState(refreshList.get(i).getChannel(), new StringType(res.get(i).getAsString()));
            }
            if (refreshList.get(i).getType().equals("Switch")) {
                updateState(refreshList.get(i).getChannel(),
                        res.get(i).getAsString().equals("on") ? OnOffType.ON : OnOffType.OFF);
            }
        }
        return true;
    }

    @Override
    protected boolean initializeData() {
        initalizeNetworkCache();
        // For testing only.. this should load the possible properties & actions per device
        // NB, ones working properly, this action should be done once the type is known
        checkChannelStructure();
        this.miioCom = getConnection();
        if (miioCom != null) {
            updateStatus(ThingStatus.ONLINE);
            checkDeviceType();
            checkChannelStructure();
        } else {
            updateStatus(ThingStatus.OFFLINE);
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

    private boolean buildChannelStructure(String deviceName) {
        // TODO: This still needs significant cleanup but should be functional
        logger.debug("Building Channel Structure for {} - Model: {}", getThing().getUID().toString(), deviceName);
        URL fn;
        try {
            Bundle bundle = bundleContext.getBundle();
            fn = bundle.getEntry(MiIoBindingConstants.DATABASE_PATH + deviceName + ".json");
            logger.debug("bundle: {}, {}, {}", bundle, fn.getFile(), fn);
        } catch (Exception e) {
            logger.warn("Database entry for model '{}' cannot be found.", deviceName);
            return false;
        }
        try {
            JsonObject deviceMapping = Utils.convertFileToJSON(fn);
            // TODO: Change to Trace later onwards
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
                channelsAdded += addChannel(thingBuilder, miChannel.getChannel(), miChannel.getChannelType(),
                        miChannel.getType(), miChannel.getFriendlyName()) ? 1 : 0;
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
        ChannelUID channelUID = new ChannelUID(getThing().getUID(), channel);
        ChannelTypeUID channelTypeUID = new ChannelTypeUID(MiIoBindingConstants.BINDING_ID, channelType);

        // TODO: only for testing. This should not be done finally. Channel only to be added when not there
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
}