/*-
 * #%L
 * HAPI FHIR JPA Server
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
package ca.uhn.fhir.jpa.partition;

import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.entity.PartitionEntity;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class RequestPartitionHelperSvc extends BaseRequestPartitionHelperSvc {

	@Autowired
	private IPartitionLookupSvc myPartitionConfigSvc;

	public RequestPartitionHelperSvc() {}
	;

	@Override
	public RequestPartitionId validateAndNormalizePartitionIds(RequestPartitionId theRequestPartitionId) {
		List<String> names = null;
		List<Integer> partitionIds = null;
		for (int i = 0; i < theRequestPartitionId.getPartitionIds().size(); i++) {

			PartitionEntity partition;
			Integer id = theRequestPartitionId.getPartitionIds().get(i);
			if (id == null) {
				partition = null;
				if (myPartitionSettings.getDefaultPartitionId() != null) {
					if (partitionIds == null) {
						partitionIds = new ArrayList<>(theRequestPartitionId.getPartitionIds());
					}
					partitionIds.set(i, myPartitionSettings.getDefaultPartitionId());
				}
			} else {
				try {
					partition = myPartitionConfigSvc.getPartitionById(id);
				} catch (IllegalArgumentException e) {
					String msg = myFhirContext
							.getLocalizer()
							.getMessage(
									BaseRequestPartitionHelperSvc.class,
									"unknownPartitionId",
									theRequestPartitionId.getPartitionIds().get(i));
					throw new ResourceNotFoundException(Msg.code(1316) + msg);
				}
			}

			if (theRequestPartitionId.hasPartitionNames()) {
				if (partition == null) {
					Validate.isTrue(
							theRequestPartitionId.getPartitionIds().get(i) == null,
							"Partition %s must not have an ID",
							JpaConstants.DEFAULT_PARTITION_NAME);
				} else {
					Validate.isTrue(
							Objects.equals(
									theRequestPartitionId.getPartitionNames().get(i), partition.getName()),
							"Partition name %s does not match ID %s",
							theRequestPartitionId.getPartitionNames().get(i),
							theRequestPartitionId.getPartitionIds().get(i));
				}
			} else {
				if (names == null) {
					names = new ArrayList<>();
				}
				if (partition != null) {
					names.add(partition.getName());
				} else {
					names.add(null);
				}
			}
		}

		if (names != null) {
			List<Integer> partitionIdsToUse = theRequestPartitionId.getPartitionIds();
			if (partitionIds != null) {
				partitionIdsToUse = partitionIds;
			}
			return RequestPartitionId.forPartitionIdsAndNames(
					names, partitionIdsToUse, theRequestPartitionId.getPartitionDate());
		}

		return theRequestPartitionId;
	}

	@Override
	public RequestPartitionId validateAndNormalizePartitionNames(RequestPartitionId theRequestPartitionId) {
		List<Integer> ids = null;
		for (int i = 0; i < theRequestPartitionId.getPartitionNames().size(); i++) {

			PartitionEntity partition;
			try {
				partition = myPartitionConfigSvc.getPartitionByName(
						theRequestPartitionId.getPartitionNames().get(i));
			} catch (IllegalArgumentException e) {
				String msg = myFhirContext
						.getLocalizer()
						.getMessage(
								BaseRequestPartitionHelperSvc.class,
								"unknownPartitionName",
								theRequestPartitionId.getPartitionNames().get(i));
				throw new ResourceNotFoundException(Msg.code(1317) + msg);
			}

			if (theRequestPartitionId.hasPartitionIds()) {
				Integer partitionId = theRequestPartitionId.getPartitionIds().get(i);
				if (partition == null) {
					Validate.isTrue(
							partitionId == null || partitionId.equals(myPartitionSettings.getDefaultPartitionId()),
							"Partition %s must not have an ID",
							JpaConstants.DEFAULT_PARTITION_NAME);
				} else {
					Validate.isTrue(
							Objects.equals(partitionId, partition.getId()),
							"Partition ID %s does not match name %s",
							partitionId,
							theRequestPartitionId.getPartitionNames().get(i));
				}
			} else {
				if (ids == null) {
					ids = new ArrayList<>();
				}
				if (partition != null) {
					ids.add(partition.getId());
				} else {
					ids.add(myPartitionSettings.getDefaultPartitionId());
				}
			}
		}

		if (ids != null) {
			return RequestPartitionId.forPartitionIdsAndNames(
					theRequestPartitionId.getPartitionNames(), ids, theRequestPartitionId.getPartitionDate());
		}

		return theRequestPartitionId;
	}
}
