/*-
 * #%L
 * HAPI FHIR Subscription Server
 * %%
 * Copyright (C) 2014 - 2025 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.subscription.channel.subscription;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class SubscriptionConsumerCache {
	private static final Logger ourLog = LoggerFactory.getLogger(SubscriptionConsumerCache.class);

	private final Map<String, SubscriptionResourceDeliveryMessageConsumer> myCache = new ConcurrentHashMap<>();

	public SubscriptionResourceDeliveryMessageConsumer get(String theChannelName) {
		return myCache.get(theChannelName);
	}

	public int size() {
		return myCache.size();
	}

	public void put(String theChannelName, SubscriptionResourceDeliveryMessageConsumer theValue) {
		myCache.put(theChannelName, theValue);
	}

	void closeAndRemove(String theChannelName) {
		Validate.notBlank(theChannelName);

		SubscriptionResourceDeliveryMessageConsumer subscriptionResourceDeliveryMessageConsumer =
				myCache.remove(theChannelName);
		if (subscriptionResourceDeliveryMessageConsumer != null) {
			subscriptionResourceDeliveryMessageConsumer.close();
		}
	}

	public boolean containsKey(String theChannelName) {
		return myCache.containsKey(theChannelName);
	}

	@VisibleForTesting
	void logForUnitTest() {
		for (String key : myCache.keySet()) {
			ourLog.info("SubscriptionConsumerCache: {}", key);
		}
	}
}
