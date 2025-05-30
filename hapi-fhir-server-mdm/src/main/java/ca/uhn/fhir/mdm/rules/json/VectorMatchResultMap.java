/*-
 * #%L
 * HAPI FHIR - Master Data Management
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
package ca.uhn.fhir.mdm.rules.json;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.mdm.api.MdmMatchResultEnum;
import jakarta.annotation.Nonnull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class VectorMatchResultMap {
	private final MdmRulesJson myMdmRulesJson;
	private Map<Long, MdmMatchResultEnum> myVectorToMatchResultMap = new HashMap<>();
	private Set<Long> myMatchVectors = new HashSet<>();
	private Set<Long> myPossibleMatchVectors = new HashSet<>();
	private Map<Long, String> myVectorToFieldMatchNamesMap = new HashMap<>();

	VectorMatchResultMap(MdmRulesJson theMdmRulesJson) {
		myMdmRulesJson = theMdmRulesJson;
		// no reason to hold the entire mdmRulesJson here
		initMap();
	}

	private void initMap() {
		for (Map.Entry<String, MdmMatchResultEnum> entry :
				myMdmRulesJson.getMatchResultMap().entrySet()) {
			put(entry.getKey(), entry.getValue());
		}
	}

	@Nonnull
	public MdmMatchResultEnum get(Long theMatchVector) {
		return myVectorToMatchResultMap.computeIfAbsent(theMatchVector, this::computeMatchResult);
	}

	private MdmMatchResultEnum computeMatchResult(Long theVector) {
		if (myMatchVectors.stream().anyMatch(v -> (v & theVector) == v)) {
			return MdmMatchResultEnum.MATCH;
		}
		if (myPossibleMatchVectors.stream().anyMatch(v -> (v & theVector) == v)) {
			return MdmMatchResultEnum.POSSIBLE_MATCH;
		}
		return MdmMatchResultEnum.NO_MATCH;
	}

	public Set<String> getMatchedRules(Long theVector) {
		if (theVector == null) {
			return new HashSet<>();
		}
		return myVectorToFieldMatchNamesMap.entrySet().stream()
				.filter(e -> ((e.getKey() & theVector) == e.getKey()))
				.map(Map.Entry::getValue)
				.collect(Collectors.toSet());
	}

	private void put(String theFieldMatchNames, MdmMatchResultEnum theMatchResult) {
		long vector = getVector(theFieldMatchNames);
		myVectorToFieldMatchNamesMap.put(vector, theFieldMatchNames);
		myVectorToMatchResultMap.put(vector, theMatchResult);
		if (theMatchResult == MdmMatchResultEnum.MATCH) {
			myMatchVectors.add(vector);
		} else if (theMatchResult == MdmMatchResultEnum.POSSIBLE_MATCH) {
			myPossibleMatchVectors.add(vector);
		}
	}

	/**
	 * Calculates the vector for the match rule.
	 *
	 * This calculation uses the index of the match field and the binary
	 * shift operator ({@code <<}) to calculate the field.
	 *
	 * See {@link ca.uhn.fhir.mdm.rules.matcher.util.MatchRuleUtil#MAX_RULE_COUNT}
	 *
	 * @param theFieldMatchNames the match rule (eg, "match_field1,match_field2")
	 * @return the vector expressed as a long
	 */
	public long getVector(String theFieldMatchNames) {
		long retval = 0;
		for (String fieldMatchName : splitFieldMatchNames(theFieldMatchNames)) {
			int index = getFieldMatchIndex(fieldMatchName);
			if (index == -1) {
				throw new ConfigurationException(Msg.code(1523) + "There is no matchField with name " + fieldMatchName);
			}
			retval |= (1L << index);
		}
		return retval;
	}

	@Nonnull
	static String[] splitFieldMatchNames(String theFieldMatchNames) {
		return theFieldMatchNames.split(",\\s*");
	}

	private int getFieldMatchIndex(final String theFieldMatchName) {
		for (int i = 0; i < myMdmRulesJson.size(); ++i) {
			if (myMdmRulesJson.get(i).getName().equals(theFieldMatchName)) {
				return i;
			}
		}
		return -1;
	}

	public String getFieldMatchNames(long theVector) {
		return myVectorToFieldMatchNamesMap.get(theVector);
	}

	public Set<String> getAllFieldMatchNames() {
		return myVectorToFieldMatchNamesMap.keySet().stream()
				.map(key -> myVectorToFieldMatchNamesMap.get(key))
				.collect(Collectors.toSet());
	}
}
