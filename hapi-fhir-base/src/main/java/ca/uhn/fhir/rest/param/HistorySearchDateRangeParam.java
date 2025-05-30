/*-
 * #%L
 * HAPI FHIR - Core Library
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
package ca.uhn.fhir.rest.param;

import java.util.Collections;
import java.util.Map;

public class HistorySearchDateRangeParam extends DateRangeParam {
	/**
	 * Constructor
	 *
	 * @since 8.0.0
	 */
	public HistorySearchDateRangeParam() {
		this(Collections.emptyMap(), new DateRangeParam(), null);
	}

	/**
	 * Constructor
	 */
	public HistorySearchDateRangeParam(
			Map<String, String[]> theParameters, DateRangeParam theDateRange, Integer theOffset) {
		super(theDateRange);
		this.myOffset = theOffset;

		this.myHistorySearchType = theParameters == null
				? null
				: theParameters.keySet().stream()
						.map(key -> HistorySearchStyleEnum.parse(key))
						.filter(type -> type != null)
						.findAny()
						.orElse(null);
	}

	private HistorySearchStyleEnum myHistorySearchType;

	private Integer myOffset;

	public HistorySearchStyleEnum getHistorySearchType() {
		return myHistorySearchType;
	}

	public Integer getOffset() {
		return myOffset;
	}
}
