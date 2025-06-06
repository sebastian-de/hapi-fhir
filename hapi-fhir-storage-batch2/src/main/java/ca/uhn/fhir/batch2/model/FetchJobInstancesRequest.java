/*-
 * #%L
 * HAPI FHIR JPA Server - Batch2 Task Processor
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
package ca.uhn.fhir.batch2.model;

import jakarta.annotation.Nonnull;

import java.util.HashSet;
import java.util.Set;

public class FetchJobInstancesRequest {

	private final String myJobDefinition;

	private final String myParameters;

	private final Set<StatusEnum> myStatuses = new HashSet<>();

	public FetchJobInstancesRequest(@Nonnull String theJobDefinition, @Nonnull String theParameters) {
		myJobDefinition = theJobDefinition;
		myParameters = theParameters;
	}

	public FetchJobInstancesRequest(
			@Nonnull String theJobDefinition, @Nonnull String theParameters, StatusEnum... theStatuses) {
		myJobDefinition = theJobDefinition;
		myParameters = theParameters;
		for (StatusEnum status : theStatuses) {
			addStatus(status);
		}
	}

	public String getJobDefinition() {
		return myJobDefinition;
	}

	public String getParameters() {
		return myParameters;
	}

	private void addStatus(StatusEnum theStatusEnum) {
		myStatuses.add(theStatusEnum);
	}

	public Set<StatusEnum> getStatuses() {
		return myStatuses;
	}
}
