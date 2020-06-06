/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.bridge;

import java.util.HashMap;
import java.util.Map;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skeletal ONOS application component
 */
@Component(immediate = true,
           service = {SomeInterface.class}/*,
           property = {
               "someProperty=Some Default String Value",
           }*/)
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /* Some configurable property */
    // private String someProperty;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    private ReactivePacketProcessor processor = new ReactivePacketProcessor();

    private ApplicationId appId;

    // Source MAC address => Incoming port
    private Map<DeviceId, HashMap<MacAddress, PortNumber>> switchTable = new HashMap<DeviceId, HashMap<MacAddress, PortNumber>>();

    private final int FlowPriority = 10;
    private final int FlowTimeout = 10;

    @Activate
    protected void activate() {
        // cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("nctu.winlab.bridge");

        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestIntercepts();

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        // cfgService.unregisterProperties(getClass(), false);
        withdrawIntercepts();
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        // Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            // someProperty = get(properties, "someProperty");
            requestIntercepts();
        }
        log.info("Reconfigured");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

    /**
    * Request packet in via packet service
    */
    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        selector.matchEthType(Ethernet.TYPE_IPV6);  // default enable ipv6
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    /**
    * Cancel request for packet in via packet service
    */
    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
        selector.matchEthType(Ethernet.TYPE_IPV6);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private class ReactivePacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            // Stop processing if the packet has been handled, since we can't do any more to it
            if (context.isHandled()) {
                return;
            }

            InboundPacket packet = context.inPacket();
            Ethernet ethPacket = packet.parsed();  // parse the packet-in
            // Bail if this is null or is a control packet
            if ((ethPacket == null) || isControlPacket(ethPacket)) {
                log.info("ethPacket is null or is a control packet");
                return;
            }
            
            // Do not process LLDP MAC address in any way
            if (ethPacket.getDestinationMAC().isLldp()) {
                log.info("ethPacket is LLDP packet");
                return;
            }

            // Update the inner-table (Mac Address => Incoming port)
            HashMap<MacAddress, PortNumber> macToPortTable;
            if (switchTable.containsKey(packet.receivedFrom().deviceId())) {
                macToPortTable = switchTable.get(packet.receivedFrom().deviceId());
            }
            else {
                macToPortTable = new HashMap<>();
                switchTable.put(packet.receivedFrom().deviceId(), macToPortTable);
            }
            macToPortTable.put(ethPacket.getSourceMAC(), packet.receivedFrom().port());

            // Lookup the inner-table to decide which port the controller should send to (Table Hit)
            if (macToPortTable.containsKey(ethPacket.getDestinationMAC())) {
                log.info("Install the flow rule");
                installRule(context, macToPortTable.get(ethPacket.getDestinationMAC()));
            }
            // Flood if not find anything (Table miss)
            else {
                log.info("Flood");
                packetOut(context, PortNumber.FLOOD);
            }
        }
        
    }

    /**
    * Indicates whether this is a control packet, e.g. LLDP, BDDP
    */
    private boolean isControlPacket(Ethernet eth) {
        short type = eth.getEtherType();
        return type == Ethernet.TYPE_LLDP || type == Ethernet.TYPE_BSN;
    }

    /**
    * Sends a packet out the specified port
    */
    private void packetOut(PacketContext context, PortNumber portNumber/*, ReactiveForwardMetrics macMetrics*/) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }

    /**
    * Install a flow rule to forward the packet to the specified port
    */
    private void installRule(PacketContext context, PortNumber portNumber/*, ReactiveForwardMetrics macMetrics*/) {
        Ethernet inPacket = context.inPacket().parsed();

        // If ARP packet than forward directly to output port
        if (inPacket.getEtherType() == Ethernet.TYPE_ARP) {
            packetOut(context, portNumber);
            return;
        }

        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthSrc(inPacket.getSourceMAC()).matchEthDst(inPacket.getDestinationMAC());

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(portNumber)
                .build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .withPriority(FlowPriority)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makeTemporary(FlowTimeout)
                .add();

        flowObjectiveService.forward(context.inPacket().receivedFrom().deviceId(),
                forwardingObjective);

        packetOut(context, portNumber);
    }

}
