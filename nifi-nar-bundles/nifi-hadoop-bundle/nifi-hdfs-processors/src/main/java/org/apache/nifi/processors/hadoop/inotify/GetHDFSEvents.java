/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.hadoop.inotify;


import org.apache.hadoop.hdfs.DFSInotifyEventInputStream;
import org.apache.hadoop.hdfs.client.HdfsAdmin;
import org.apache.hadoop.hdfs.inotify.Event;
import org.apache.hadoop.hdfs.inotify.EventBatch;
import org.apache.hadoop.hdfs.inotify.MissingEventsException;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.Stateful;
import org.apache.nifi.annotation.behavior.TriggerSerially;
import org.apache.nifi.annotation.behavior.TriggerWhenEmpty;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.state.Scope;
import org.apache.nifi.components.state.StateManager;
import org.apache.nifi.components.state.StateMap;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.hadoop.AbstractHadoopProcessor;
import org.apache.nifi.processors.hadoop.FetchHDFS;
import org.apache.nifi.processors.hadoop.GetHDFS;
import org.apache.nifi.processors.hadoop.ListHDFS;
import org.apache.nifi.processors.hadoop.PutHDFS;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;


@TriggerSerially
@TriggerWhenEmpty
@Tags({"hadoop", "events", "inotify", "notifications", "filesystem"})
@WritesAttributes({
        @WritesAttribute(attribute = EventAttributes.MIME_TYPE, description = "This is always application/json."),
        @WritesAttribute(attribute = EventAttributes.EVENT_TYPE, description = "This will specify the specific HDFS notification event type. Currently there are six types of events " +
                "(append, close, create, metadata, rename, and unlink)."),
        @WritesAttribute(attribute = EventAttributes.EVENT_PATH, description = "The specific path that the event is tied to.")
})
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@CapabilityDescription("This processor polls the notification events provided by the HdfsAdmin API. Since this uses the HdfsAdmin APIs it is required to run as an HDFS super user. Currently there " +
        "are six types of events (append, close, create, metadata, rename, and unlink). Please see org.apache.hadoop.hdfs.inotify.Event documentation for full explanations of each event. " +
        "This processor will poll for new events based on a defined duration. For each event received a new flow file will be created with the expected attributes and the event itself serialized " +
        "to JSON and written to the flow file's content. For example, if event.type is APPEND then the content of the flow file will contain a JSON file containing the information about the " +
        "append event. If successful the flow files are sent to the 'success' relationship. Be careful of where the generated flow files are stored. If the flow files are stored in one of " +
        "processor's watch directories there will be a never ending flow of events. It is also important to be aware that this processor must consume all events. The filtering must happen within " +
        "the processor. This is because the HDFS admin's event notifications API does not have filtering.")
@Stateful(scopes = Scope.CLUSTER, description = "The last used transaction id is stored. This is used ")
@SeeAlso({GetHDFS.class, FetchHDFS.class, PutHDFS.class, ListHDFS.class})
public class GetHDFSEvents extends AbstractHadoopProcessor {
    static final PropertyDescriptor POLL_DURATION = new PropertyDescriptor.Builder()
            .name("Poll Duration")
            .description("How long to poll for HDFS events.")
            .defaultValue("1 second")
            .required(true)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    static final PropertyDescriptor HDFS_PATH_TO_WATCH = new PropertyDescriptor.Builder()
            .name("HDFS Path to Watch")
            .description("The HDFS path to get event notifications for.")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    static final PropertyDescriptor RECURSE_SUBDIRECTORIES = new PropertyDescriptor.Builder()
            .name("Recurse Subdirectories")
            .description("Determines if processor will process event notifications for all subdirectories and files below the defined HDFS path to watch.")
            .required(true)
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();

    static final PropertyDescriptor EVENT_TYPES = new PropertyDescriptor.Builder()
            .name("Event Types to Filter On")
            .description("A comma-separated list of event types to process. Valid event types are: append, close, create, metadata, rename, and unlink. Case does not matter.")
            .addValidator(new EventTypeValidator())
            .required(true)
            .defaultValue("append, close, create, metadata, rename, unlink")
            .build();

    static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("A flow file with updated information about a specific event will be sent to this relationship.")
            .build();

    private static final Set<Relationship> RELATIONSHIPS = Collections.unmodifiableSet(new HashSet<>(Collections.singletonList(REL_SUCCESS)));
    private static final String LAST_TX_ID = "last.tx.id";
    private volatile long lastTxId = -1L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        List<PropertyDescriptor> props = new ArrayList<>(properties);
        props.add(POLL_DURATION);
        props.add(HDFS_PATH_TO_WATCH);
        props.add(RECURSE_SUBDIRECTORIES);
        props.add(EVENT_TYPES);
        return Collections.unmodifiableList(props);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        final StateManager stateManager = context.getStateManager();

        try {
            StateMap state = stateManager.getState(Scope.CLUSTER);
            String txIdAsString = state.get(LAST_TX_ID);

            if (txIdAsString != null && !"".equals(txIdAsString)) {
                lastTxId = Long.parseLong(txIdAsString);
            }
        } catch (IOException e) {
            getLogger().error("Unable to retrieve last transaction ID. Must retrieve last processed transaction ID before processing can occur.", e);
            context.yield();
            return;
        }

        try {
            final TimeUnit pollDurationTimeUnit = TimeUnit.MICROSECONDS;
            final long pollDuration = context.getProperty(POLL_DURATION).asTimePeriod(pollDurationTimeUnit);
            final DFSInotifyEventInputStream eventStream = lastTxId == -1L ? getHdfsAdmin().getInotifyEventStream() : getHdfsAdmin().getInotifyEventStream(lastTxId);
            final EventBatch eventBatch = getEventBatch(eventStream, pollDuration, pollDurationTimeUnit);

            if (eventBatch != null && eventBatch.getEvents() != null) {
                if (eventBatch.getEvents().length > 0) {
                    List<FlowFile> flowFiles = new ArrayList<>(eventBatch.getEvents().length);
                    for (Event e : eventBatch.getEvents()) {
                        if (toProcessEvent(context, e)) {
                            getLogger().debug("Creating flow file for event.");
                            FlowFile flowFile = session.create();
                            flowFile = session.putAttribute(flowFile, EventAttributes.MIME_TYPE, "application/json");
                            flowFile = session.putAttribute(flowFile, EventAttributes.EVENT_TYPE, e.getEventType().name());
                            flowFile = session.putAttribute(flowFile, EventAttributes.EVENT_PATH, getPath(e));
                            flowFile = session.write(flowFile, new OutputStreamCallback() {
                                @Override
                                public void process(OutputStream out) throws IOException {
                                    out.write(OBJECT_MAPPER.writeValueAsBytes(e));
                                }
                            });
                            flowFiles.add(flowFile);
                        }
                    }

                    getLogger().debug("Transferring all event flow files.");
                    session.transfer(flowFiles, REL_SUCCESS);
                }

                lastTxId = eventBatch.getTxid();
            }
        } catch (IOException | InterruptedException e) {
            getLogger().error("Unable to get notification information: {}", new Object[]{e});
            throw new ProcessException(e);
        } catch (MissingEventsException e) {
            // set lastTxId to -1 and update state. This may cause events not to be processed. The reason this exception is thrown is described in the
            // org.apache.hadoop.hdfs.client.HdfsAdmin#getInotifyEventStrea API. It suggests tuning a couple parameters if this API is used.
            lastTxId = -1L;
            getLogger().error("Unable to get notification information. Setting transaction id to -1. This may cause some events to get missed. " +
                    "Please see javadoc for org.apache.hadoop.hdfs.client.HdfsAdmin#getInotifyEventStream: {}", new Object[]{e});
        }

        updateClusterStateForTxId(stateManager);
    }

    private EventBatch getEventBatch(DFSInotifyEventInputStream eventStream, long duration, TimeUnit timeUnit) throws IOException, InterruptedException, MissingEventsException {
        // According to the inotify API we should retry a few times if poll throws an IOException.
        // Please see org.apache.hadoop.hdfs.DFSInotifyEventInputStream#poll for documentation.
        int i = 0;
        while (true) {
            try {
                i += 1;
                return eventStream.poll(duration, timeUnit);
            } catch (IOException e) {
                if (i > 3) {
                    getLogger().debug("Failed to poll for event batch. Reached max retry times.", e);
                    throw e;
                } else {
                    getLogger().debug("Attempt {} failed to poll for event batch. Retrying.", new Object[]{i});
                }
            }
        }
    }

    private void updateClusterStateForTxId(StateManager stateManager) {
        try {
            Map<String, String> newState = new HashMap<>(stateManager.getState(Scope.CLUSTER).toMap());
            newState.put(LAST_TX_ID, String.valueOf(lastTxId));
            stateManager.setState(newState, Scope.CLUSTER);
        } catch (IOException e) {
            getLogger().warn("Failed to update cluster state for last txId. It is possible data replication may occur.", e);
        }
    }

    protected HdfsAdmin getHdfsAdmin() {
        try {
            // Currently HdfsAdmin is the only public API that allows access to the inotify API. Because of this we need to have super user rights in HDFS.
            return new HdfsAdmin(getFileSystem().getUri(), getFileSystem().getConf());
        } catch (IOException e) {
            getLogger().error("Unable to get and instance of HDFS admin. You must be an HDFS super user to view HDFS events.");
            throw new ProcessException(e);
        }
    }

    private boolean toProcessEvent(ProcessContext context, Event e) {
        final String[] eventTypes = context.getProperty(EVENT_TYPES).getValue().split(",");
        for (String name : eventTypes) {
            if (name.trim().equalsIgnoreCase(e.getEventType().name())) {
                final String watchDirectory = context.getProperty(HDFS_PATH_TO_WATCH).getValue();
                final boolean recursive = context.getProperty(RECURSE_SUBDIRECTORIES).asBoolean();
                return toProcessPath(e, watchDirectory, recursive);
            }
        }

        return false;
    }

    private boolean toProcessPath(Event e, String watchDirectory, boolean recursive) {
        final String path = removeEndingSlashIfExists(getPath(e));
        if (path == null || "".equals(path) || watchDirectory == null || "".equals(watchDirectory)) {
            return false;
        } else {
            final String watchPath = removeEndingSlashIfExists(watchDirectory);
            return recursive ? path.startsWith(watchPath) : path.equals(watchPath);
        }
    }

    private String removeEndingSlashIfExists(String path) {
        return path != null && !"".equals(path) && path.endsWith("/") ?  path.substring(0, path.length() - 1) : path;
    }

    private String getPath(Event event) {
        if (event == null || event.getEventType() == null) {
            throw new IllegalArgumentException("Event and event type must not be null.");
        }

        switch (event.getEventType()) {
            case CREATE: return ((Event.CreateEvent) event).getPath();
            case CLOSE: return ((Event.CloseEvent) event).getPath();
            case APPEND: return ((Event.AppendEvent) event).getPath();
            case RENAME: return ((Event.RenameEvent) event).getSrcPath();
            case METADATA: return ((Event.MetadataUpdateEvent) event).getPath();
            case UNLINK: return ((Event.UnlinkEvent) event).getPath();
            default: throw new IllegalArgumentException("Unsupported event type.");
        }
    }
}
