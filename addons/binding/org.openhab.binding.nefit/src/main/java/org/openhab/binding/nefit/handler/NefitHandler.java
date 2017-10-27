/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.nefit.handler;

import static org.openhab.binding.nefit.NefitBindingConstants.*;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.nefit.NefitBindingConfiguration;
import org.openhab.binding.nefit.internal.NefitMessageListener;
import org.openhab.binding.nefit.internal.transport.NefitConnection;
import org.openhab.binding.nefit.parser.HeaderParser;
import org.openhab.binding.nefit.parser.MessageParser;
import org.openhab.binding.nefit.parser.OutdoorTemp;
import org.openhab.binding.nefit.parser.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/*
import com.google.gson.JsonArray;
  import com.google.gson.JsonElement;
  import com.google.gson.JsonObject;
  import com.google.gson.JsonParser;
*/

/* The {@link nefitHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Marcel Verpaalen - Initial contribution
 */
public class NefitHandler extends BaseThingHandler implements NefitMessageListener {

    private String host;
    long port;
    Logger logger = LoggerFactory.getLogger(NefitHandler.class);
    private ScheduledFuture<?> pollingJob;
    private NefitConnection con;

    public NefitHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            logger.debug("Refreshing {}", channelUID);
            updateData();
        } else {
            logger.warn("This binding is a read-only binding and cannot handle commands");
        }
    }

    @Override
    public void initialize() {
        logger.debug("Initializing nefit handler '{}'", getThing().getUID());

        NefitBindingConfiguration config = getConfigAs(NefitBindingConfiguration.class);

        int pollingPeriod = config.refreshInterval;

        updateProperty(Thing.PROPERTY_VENDOR, "nefit");

        con = new NefitConnection(config.serialNumber, config.accessKey, config.password);
        con.registerListener(this);
        pollingJob = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                updateData();
            }
        }, 0, pollingPeriod, TimeUnit.SECONDS);
        logger.debug("Polling job scheduled to run every {} sec. for '{}'", pollingPeriod, getThing().getUID());
        updateStatus(ThingStatus.OFFLINE);
    }

    @Override
    public void dispose() {
        logger.debug("Disposing nefit handler '{}'", getThing().getUID());
        if (pollingJob != null) {
            pollingJob.cancel(true);
            pollingJob = null;
        }
    }

    private synchronized void updateData() {
        logger.debug("Update Nefit Easy data '{}'", getThing().getUID());
        con.send(OutdoorTemp.ENDPOINT, "");
        scheduler.schedule(() -> {
            con.send(Status.ENDPOINT, "");
        }, 10, TimeUnit.SECONDS);
    }

    @Override
    public void onDataReceived(String message) {
        updateStatus(ThingStatus.ONLINE);

        logger.info("received message: {}", message);

        MessageParser parser = new HeaderParser(message);
        logger.info("received id: {}", parser.getId());

        switch (parser.getId()) {
            case OutdoorTemp.ENDPOINT:
                updateState(CHANNEL_OUTDOOR_TEMP, new DecimalType(new OutdoorTemp(message).getTemperature()));
                break;
            case Status.ENDPOINT:
                updateState(CHANNEL_ROOM_TEMP, new DecimalType(new Status(message).getTemperature()));
                updateState(CHANNEL_SET_TEMP, new DecimalType(new Status(message).getsetPointTemperature()));
                break;
            default:
                logger.debug("Parsing {} not implemented", parser.getId());
        }
    }
}
