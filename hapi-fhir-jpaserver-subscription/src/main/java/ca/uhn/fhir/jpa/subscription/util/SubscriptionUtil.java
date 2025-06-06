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
package ca.uhn.fhir.jpa.subscription.util;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.model.entity.PartitionablePartitionId;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

/**
 * Utilities for working with the subscription resource
 */
public class SubscriptionUtil {

	public static RequestDetails createRequestDetailForPartitionedRequest(CanonicalSubscription theSubscription) {
		RequestPartitionId requestPartitionId =
				new PartitionablePartitionId(theSubscription.getRequestPartitionId(), null).toPartitionId();

		if (theSubscription.isCrossPartitionEnabled()) {
			requestPartitionId = RequestPartitionId.allPartitions();
		}

		return new SystemRequestDetails().setRequestPartitionId(requestPartitionId);
	}
}
