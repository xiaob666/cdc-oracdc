/**
 * Copyright (c) 2018-present, http://a2-solutions.eu
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package eu.solutions.a2.cdc.oracle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.sink.SinkConnector;
import org.apache.log4j.Logger;

import eu.solutions.a2.cdc.oracle.kafka.connect.OraCdcJdbcSinkConnectorConfig;
import eu.solutions.a2.cdc.oracle.kafka.connect.OraCdcJdbcSinkTask;
import eu.solutions.a2.cdc.oracle.utils.Version;

public class OraCdcJdbcSinkConnector extends SinkConnector {

	private static final Logger LOGGER = Logger.getLogger(OraCdcJdbcSinkConnector.class);

	private Map<String, String> props;

	@Override
	public String version() {
		return Version.getVersion();
	}

	@Override
	public void start(Map<String, String> props) {
		LOGGER.info("Starting oracdc Sink Connector");
		this.props = props;
	}

	@Override
	public void stop() {
	}

	@Override
	public Class<? extends Task> taskClass() {
		return OraCdcJdbcSinkTask.class;
	}

	@Override
	public List<Map<String, String>> taskConfigs(int maxTasks) {
		final List<Map<String, String>> configs = new ArrayList<>(maxTasks);
		for (int i = 0; i < maxTasks; i++) {
			configs.add(props);
		}
		return configs;
	}

	@Override
	public ConfigDef config() {
		return OraCdcJdbcSinkConnectorConfig.config();
	}

}