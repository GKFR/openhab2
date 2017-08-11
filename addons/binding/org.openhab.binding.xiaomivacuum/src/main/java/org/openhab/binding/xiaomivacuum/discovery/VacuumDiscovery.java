/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.xiaomivacuum.discovery;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.xiaomivacuum.XiaomiVacuumBindingConstants;
import org.openhab.binding.xiaomivacuum.internal.Message;
import org.openhab.binding.xiaomivacuum.internal.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link VacuumDiscovery} is responsible for discovering new Xiaomi Robot Vacuums
 * and their token
 *
 * @author Marcel Verpaalen - Initial contribution
 *
 */
public class VacuumDiscovery extends AbstractDiscoveryService {

    /** The refresh interval for background discovery */
    private static final long SEARCH_INTERVAL = 600;
    private static final int TIMEOUT = 2000;
    private ScheduledFuture<?> roboDiscoveryJob;

    private final Logger logger = LoggerFactory.getLogger(VacuumDiscovery.class);

    public VacuumDiscovery() throws IllegalArgumentException {
        super(15);
    }

    @Override
    protected void startBackgroundDiscovery() {
        logger.debug("Start Xiaomi Robot Vacuum background discovery");
        if (roboDiscoveryJob == null || roboDiscoveryJob.isCancelled()) {
            roboDiscoveryJob = scheduler.scheduleWithFixedDelay(() -> discover(), 0, SEARCH_INTERVAL, TimeUnit.SECONDS);
        }
    }

    @Override
    protected void stopBackgroundDiscovery() {
        logger.debug("Stop Xiaomi Robot Vacuum background discovery");
        if (roboDiscoveryJob != null && !roboDiscoveryJob.isCancelled()) {
            roboDiscoveryJob.cancel(true);
            roboDiscoveryJob = null;
        }
    }

    private synchronized void discover() {
        TreeSet<String> broadcastAdresses = getBroadcastAddresses();
        HashMap<String, byte[]> responses = new HashMap<String, byte[]>();
        for (String broadcastAdress : broadcastAdresses) {
            responses.putAll(sendDiscoveryRequest(broadcastAdress));
        }
        for (Entry<String, byte[]> i : responses.entrySet()) {
            logger.trace("Discovery responses from : {}:{}", i.getKey(), Utils.getSpacedHex(i.getValue()));
            Message msg = new Message(i.getValue());
            String token = Utils.getHex(msg.getChecksum());
            String id = Utils.getHex(msg.getSerialByte());
            ThingUID uid = new ThingUID(XiaomiVacuumBindingConstants.THING_TYPE_VACUUM, id);
            logger.debug("Discovered Xiaomi Robot Vacuum {} at {}", id, i.getKey());
            if (token.equals("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")) {
                logger.debug(
                        "No token discovered for device {}. To discover token reset the vacuum & connect to it's wireless network and re-run discovery",
                        id);
                thingDiscovered(DiscoveryResultBuilder.create(uid)
                        .withProperty(XiaomiVacuumBindingConstants.PROPERTY_HOST_IP, i.getKey())
                        .withLabel("Xiaomi Robot Vacuum").build());
            } else {
                logger.debug("Discovered token for device {}: {} ('{}')", id, token, new String(msg.getChecksum()));
                thingDiscovered(DiscoveryResultBuilder.create(uid)
                        .withProperty(XiaomiVacuumBindingConstants.PROPERTY_HOST_IP, i.getKey())
                        .withProperty(XiaomiVacuumBindingConstants.PROPERTY_TOKEN, token)
                        .withLabel("Xiaomi Robot Vacuum").build());
            }
        }
    }

    /**
     * @return broadcast addresses for all interfaces
     */
    private TreeSet<String> getBroadcastAddresses() {
        TreeSet<String> broadcastAddresses = new TreeSet<String>();
        try {
            broadcastAddresses.add("224.0.0.1");
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                try {
                    NetworkInterface networkInterface = interfaces.nextElement();
                    if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                        continue;
                    }
                    for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                        String address = interfaceAddress.getBroadcast().getHostAddress();
                        if (address != null) {
                            broadcastAddresses.add(address);
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        } catch (Exception e) {
            logger.trace("Error collecting broadcast addresses: {}", e.getMessage(), e);
        }
        return broadcastAddresses;
    }

    public HashMap<String, byte[]> sendDiscoveryRequest(String ipAddress) {
        HashMap<String, byte[]> responses = new HashMap<String, byte[]>();

        try (DatagramSocket clientSocket = new DatagramSocket()) {
            clientSocket.setReuseAddress(true);
            clientSocket.setBroadcast(true);
            clientSocket.setSoTimeout(TIMEOUT);
            byte[] sendData = XiaomiVacuumBindingConstants.DISCOVER_STRING;
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(ipAddress),
                    XiaomiVacuumBindingConstants.PORT);
            for (int i = 1; i <= 2; i++) {
                clientSocket.send(sendPacket);
            }
            sendPacket.setData(new byte[1024]);
            while (true) {
                clientSocket.receive(sendPacket);
                responses.put(sendPacket.getAddress().getHostAddress(), sendPacket.getData());
            }
        } catch (Exception e) {
            logger.trace("Discovery on {} error: {}", ipAddress, e.getMessage());
        }
        return responses;
    }

    @Override
    public Set<ThingTypeUID> getSupportedThingTypes() {
        return XiaomiVacuumBindingConstants.SUPPORTED_THING_TYPES_UIDS;
    }

    @Override
    protected void startScan() {
        logger.debug("Start Xiaomi Robot Vaccum discovery");
        discover();
        logger.debug("Xiaomi Robot Vaccum discovery done");
    }
}
