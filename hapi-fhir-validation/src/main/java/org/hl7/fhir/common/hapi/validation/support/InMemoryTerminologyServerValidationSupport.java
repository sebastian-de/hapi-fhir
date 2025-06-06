package org.hl7.fhir.common.hapi.validation.support;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import ca.uhn.fhir.context.support.ValueSetExpansionOptions;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.util.FhirVersionIndependentConcept;
import ca.uhn.hapi.converters.canonical.VersionCanonicalizer;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.dstu2.model.ValueSet;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r5.model.CanonicalType;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.Enumerations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.contains;
import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.substringAfter;
import static org.apache.commons.lang3.StringUtils.substringBefore;
import static org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService.getFhirVersionEnum;

/**
 * This class is a basic in-memory terminology service, designed to expand ValueSets and validate codes
 * completely in-memory. It is suitable for runtime validation purposes where no dedicated terminology
 * service exists (either an internal one such as the HAPI FHIR JPA terminology service, or an
 * external term service API)
 */
@SuppressWarnings("EnhancedSwitchMigration")
public class InMemoryTerminologyServerValidationSupport implements IValidationSupport {
	private static final String OUR_PIPE_CHARACTER = "|";
	private final FhirContext myCtx;
	private VersionCanonicalizer myVersionCanonicalizer;
	private IssueSeverity myIssueSeverityForCodeDisplayMismatch = IssueSeverity.WARNING;

	/**
	 * Constructor
	 *
	 * @param theCtx A FhirContext for the FHIR version being validated
	 */
	public InMemoryTerminologyServerValidationSupport(FhirContext theCtx) {
		Validate.notNull(theCtx, "theCtx must not be null");
		myCtx = theCtx;
		myVersionCanonicalizer = new VersionCanonicalizer(theCtx);
	}

	@VisibleForTesting
	public void setVersionCanonicalizer(VersionCanonicalizer theVersionCanonicalizer) {
		myVersionCanonicalizer = theVersionCanonicalizer;
	}

	@Override
	public String getName() {
		return myCtx.getVersion().getVersion() + " In-Memory Validation Support";
	}

	/**
	 * This setting controls the validation issue severity to report when a code validation
	 * finds that the code is present in the given CodeSystem, but the display name being
	 * validated doesn't match the expected value(s). Defaults to
	 * {@link ca.uhn.fhir.context.support.IValidationSupport.IssueSeverity#WARNING}. Set this
	 * value to {@link ca.uhn.fhir.context.support.IValidationSupport.IssueSeverity#INFORMATION}
	 * if you don't want to see display name validation issues at all in resource validation
	 * outcomes.
	 *
	 * @since 7.0.0
	 */
	public IssueSeverity getIssueSeverityForCodeDisplayMismatch() {
		return myIssueSeverityForCodeDisplayMismatch;
	}

	/**
	 * This setting controls the validation issue severity to report when a code validation
	 * finds that the code is present in the given CodeSystem, but the display name being
	 * validated doesn't match the expected value(s). Defaults to
	 * {@link ca.uhn.fhir.context.support.IValidationSupport.IssueSeverity#WARNING}. Set this
	 * value to {@link ca.uhn.fhir.context.support.IValidationSupport.IssueSeverity#INFORMATION}
	 * if you don't want to see display name validation issues at all in resource validation
	 * outcomes.
	 *
	 * @param theIssueSeverityForCodeDisplayMismatch The severity. Must not be {@literal null}.
	 * @since 7.0.0
	 */
	public void setIssueSeverityForCodeDisplayMismatch(@Nonnull IssueSeverity theIssueSeverityForCodeDisplayMismatch) {
		Validate.notNull(
				theIssueSeverityForCodeDisplayMismatch, "theIssueSeverityForCodeDisplayMismatch must not be null");
		myIssueSeverityForCodeDisplayMismatch = theIssueSeverityForCodeDisplayMismatch;
	}

	@Override
	public FhirContext getFhirContext() {
		return myCtx;
	}

	@Override
	public ValueSetExpansionOutcome expandValueSet(
			ValidationSupportContext theValidationSupportContext,
			ValueSetExpansionOptions theExpansionOptions,
			@Nonnull IBaseResource theValueSetToExpand) {
		return expandValueSet(theValidationSupportContext, theValueSetToExpand, null, null);
	}

	private ValueSetExpansionOutcome expandValueSet(
			ValidationSupportContext theValidationSupportContext,
			IBaseResource theValueSetToExpand,
			String theWantSystemAndVersion,
			String theWantCode) {
		org.hl7.fhir.r5.model.ValueSet expansionR5;
		try {
			expansionR5 = expandValueSetToCanonical(
							theValidationSupportContext, theValueSetToExpand, theWantSystemAndVersion, theWantCode)
					.getValueSet();
		} catch (ExpansionCouldNotBeCompletedInternallyException e) {
			return new ValueSetExpansionOutcome(e.getMessage(), false);
		}
		if (expansionR5 == null) {
			return null;
		}

		IBaseResource expansion = myVersionCanonicalizer.valueSetFromValidatorCanonical(expansionR5);
		return new ValueSetExpansionOutcome(expansion);
	}

	private ValueSetAndMessages expandValueSetToCanonical(
			ValidationSupportContext theValidationSupportContext,
			IBaseResource theValueSetToExpand,
			@Nullable String theWantSystemUrlAndVersion,
			@Nullable String theWantCode)
			throws ExpansionCouldNotBeCompletedInternallyException {
		org.hl7.fhir.r5.model.ValueSet input = myVersionCanonicalizer.valueSetToValidatorCanonical(theValueSetToExpand);
		return expandValueSetR5(theValidationSupportContext, input, theWantSystemUrlAndVersion, theWantCode);
	}

	@Override
	public CodeValidationResult validateCodeInValueSet(
			ValidationSupportContext theValidationSupportContext,
			ConceptValidationOptions theOptions,
			String theCodeSystemUrlAndVersion,
			String theCode,
			String theDisplay,
			@Nonnull IBaseResource theValueSet) {
		ValueSetAndMessages expansion;
		String vsUrl = CommonCodeSystemsTerminologyService.getValueSetUrl(getFhirContext(), theValueSet);
		try {
			expansion = expandValueSetToCanonical(
					theValidationSupportContext, theValueSet, theCodeSystemUrlAndVersion, theCode);
		} catch (ExpansionCouldNotBeCompletedInternallyException e) {
			CodeValidationResult codeValidationResult = new CodeValidationResult();
			codeValidationResult.setSeverity(IssueSeverity.ERROR);

			String msg = "Failed to expand ValueSet '" + vsUrl + "' (in-memory). Could not validate code "
					+ theCodeSystemUrlAndVersion + "#" + theCode;
			if (e.getMessage() != null) {
				msg += ". Error was: " + e.getMessage();
			}

			codeValidationResult.setMessage(msg);
			codeValidationResult.addIssue(e.getCodeValidationIssue());
			return codeValidationResult;
		}

		if (expansion == null || expansion.getValueSet() == null) {
			return null;
		}

		if (expansion.getValueSet().getExpansion().getContains().isEmpty()) {
			IssueSeverity severity = IssueSeverity.ERROR;
			String message = "Unknown code '"
					+ getFormattedCodeSystemAndCodeForMessage(theCodeSystemUrlAndVersion, theCode)
					+ "'"
					+ createInMemoryExpansionMessageSuffix(vsUrl)
					+ (expansion.getMessages().isEmpty() ? "" : " Expansion result: " + expansion.getMessages());
			CodeValidationIssueCoding issueCoding = CodeValidationIssueCoding.NOT_IN_VS;
			CodeValidationIssueCode notFound = CodeValidationIssueCode.NOT_FOUND;
			CodeValidationResult codeValidationResult = new CodeValidationResult()
					.setSeverity(severity)
					.setMessage(message)
					.setSourceDetails(null)
					.addIssue(new CodeValidationIssue(message, severity, notFound, issueCoding));
			return codeValidationResult;
		}

		return validateCodeInExpandedValueSet(
				theValidationSupportContext,
				theOptions,
				theCodeSystemUrlAndVersion,
				theCode,
				theDisplay,
				expansion.getValueSet(),
				vsUrl);
	}

	@Override
	@Nullable
	public CodeValidationResult validateCode(
			@Nonnull ValidationSupportContext theValidationSupportContext,
			@Nonnull ConceptValidationOptions theOptions,
			String theCodeSystem,
			String theCode,
			String theDisplay,
			String theValueSetUrl) {
		IBaseResource vs;
		if (isNotBlank(theValueSetUrl)) {
			vs = theValidationSupportContext.getRootValidationSupport().fetchValueSet(theValueSetUrl);
			if (vs == null) {
				return null;
			}
		} else {
			String codeSystemUrl;
			String codeSystemVersion = null;
			int codeSystemVersionIndex = theCodeSystem.indexOf("|");
			if (codeSystemVersionIndex > -1) {
				codeSystemUrl = theCodeSystem.substring(0, codeSystemVersionIndex);
				codeSystemVersion = theCodeSystem.substring(codeSystemVersionIndex + 1);
			} else {
				codeSystemUrl = theCodeSystem;
			}
			switch (myCtx.getVersion().getVersion()) {
				case DSTU2:
				case DSTU2_HL7ORG:
					vs = new org.hl7.fhir.dstu2.model.ValueSet()
							.setCompose(new org.hl7.fhir.dstu2.model.ValueSet.ValueSetComposeComponent()
									.addInclude(new org.hl7.fhir.dstu2.model.ValueSet.ConceptSetComponent()
											.setSystem(theCodeSystem)));
					break;
				case DSTU3:
					if (codeSystemVersion != null) {
						vs = new org.hl7.fhir.dstu3.model.ValueSet()
								.setCompose(new org.hl7.fhir.dstu3.model.ValueSet.ValueSetComposeComponent()
										.addInclude(new org.hl7.fhir.dstu3.model.ValueSet.ConceptSetComponent()
												.setSystem(codeSystemUrl)
												.setVersion(codeSystemVersion)));
					} else {
						vs = new org.hl7.fhir.dstu3.model.ValueSet()
								.setCompose(new org.hl7.fhir.dstu3.model.ValueSet.ValueSetComposeComponent()
										.addInclude(new org.hl7.fhir.dstu3.model.ValueSet.ConceptSetComponent()
												.setSystem(theCodeSystem)));
					}
					break;
				case R4:
					if (codeSystemVersion != null) {
						vs = new org.hl7.fhir.r4.model.ValueSet()
								.setCompose(new org.hl7.fhir.r4.model.ValueSet.ValueSetComposeComponent()
										.addInclude(new org.hl7.fhir.r4.model.ValueSet.ConceptSetComponent()
												.setSystem(codeSystemUrl)
												.setVersion(codeSystemVersion)));
					} else {
						vs = new org.hl7.fhir.r4.model.ValueSet()
								.setCompose(new org.hl7.fhir.r4.model.ValueSet.ValueSetComposeComponent()
										.addInclude(new org.hl7.fhir.r4.model.ValueSet.ConceptSetComponent()
												.setSystem(theCodeSystem)));
					}
					break;
				case R4B:
					if (codeSystemVersion != null) {
						vs = new org.hl7.fhir.r4b.model.ValueSet()
								.setCompose(new org.hl7.fhir.r4b.model.ValueSet.ValueSetComposeComponent()
										.addInclude(new org.hl7.fhir.r4b.model.ValueSet.ConceptSetComponent()
												.setSystem(codeSystemUrl)
												.setVersion(codeSystemVersion)));
					} else {
						vs = new org.hl7.fhir.r4b.model.ValueSet()
								.setCompose(new org.hl7.fhir.r4b.model.ValueSet.ValueSetComposeComponent()
										.addInclude(new org.hl7.fhir.r4b.model.ValueSet.ConceptSetComponent()
												.setSystem(theCodeSystem)));
					}
					break;
				case R5:
					if (codeSystemVersion != null) {
						vs = new org.hl7.fhir.r5.model.ValueSet()
								.setCompose(new org.hl7.fhir.r5.model.ValueSet.ValueSetComposeComponent()
										.addInclude(new org.hl7.fhir.r5.model.ValueSet.ConceptSetComponent()
												.setSystem(codeSystemUrl)
												.setVersion(codeSystemVersion)));
					} else {
						vs = new org.hl7.fhir.r5.model.ValueSet()
								.setCompose(new org.hl7.fhir.r5.model.ValueSet.ValueSetComposeComponent()
										.addInclude(new org.hl7.fhir.r5.model.ValueSet.ConceptSetComponent()
												.setSystem(theCodeSystem)));
					}
					break;
				case DSTU2_1:
				default:
					throw new IllegalArgumentException(Msg.code(699) + "Can not handle version: "
							+ myCtx.getVersion().getVersion());
			}
		}

		ValueSetExpansionOutcome valueSetExpansionOutcome =
				expandValueSet(theValidationSupportContext, vs, theCodeSystem, theCode);
		if (valueSetExpansionOutcome == null) {
			return null;
		}

		if (valueSetExpansionOutcome.getError() != null) {
			return new CodeValidationResult()
					.setSeverity(IssueSeverity.ERROR)
					.setMessage(valueSetExpansionOutcome.getError());
		}

		IBaseResource expansion = valueSetExpansionOutcome.getValueSet();
		return validateCodeInExpandedValueSet(
				theValidationSupportContext, theOptions, theCodeSystem, theCode, theDisplay, expansion, theValueSetUrl);
	}

	private CodeValidationResult validateCodeInExpandedValueSet(
			ValidationSupportContext theValidationSupportContext,
			ConceptValidationOptions theOptions,
			String theCodeSystemUrlAndVersionToValidate,
			String theCodeToValidate,
			String theDisplayToValidate,
			IBaseResource theExpansion,
			String theValueSetUrl) {
		assert theExpansion != null;

		final CodeValidationResult codeValidationResult;

		boolean caseSensitive = true;
		IBaseResource codeSystemToValidateResource = null;
		if (!theOptions.isInferSystem() && isNotBlank(theCodeSystemUrlAndVersionToValidate)) {
			codeSystemToValidateResource = theValidationSupportContext
					.getRootValidationSupport()
					.fetchCodeSystem(theCodeSystemUrlAndVersionToValidate);
		}

		List<FhirVersionIndependentConcept> codes = new ArrayList<>();
		switch (getFhirVersionEnum(
				theValidationSupportContext.getRootValidationSupport().getFhirContext(), theExpansion)) {
			case DSTU2: {
				ca.uhn.fhir.model.dstu2.resource.ValueSet expansionVs =
						(ca.uhn.fhir.model.dstu2.resource.ValueSet) theExpansion;
				List<ca.uhn.fhir.model.dstu2.resource.ValueSet.ExpansionContains> contains =
						expansionVs.getExpansion().getContains();
				flattenAndConvertCodesDstu2(contains, codes);
				break;
			}
			case DSTU2_HL7ORG: {
				ValueSet expansionVs = (ValueSet) theExpansion;
				List<ValueSet.ValueSetExpansionContainsComponent> contains =
						expansionVs.getExpansion().getContains();
				flattenAndConvertCodesDstu2Hl7Org(contains, codes);
				break;
			}
			case DSTU3: {
				org.hl7.fhir.dstu3.model.ValueSet expansionVs = (org.hl7.fhir.dstu3.model.ValueSet) theExpansion;
				List<org.hl7.fhir.dstu3.model.ValueSet.ValueSetExpansionContainsComponent> contains =
						expansionVs.getExpansion().getContains();
				flattenAndConvertCodesDstu3(contains, codes);
				break;
			}
			case R4: {
				org.hl7.fhir.r4.model.ValueSet expansionVs = (org.hl7.fhir.r4.model.ValueSet) theExpansion;
				List<org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionContainsComponent> contains =
						expansionVs.getExpansion().getContains();
				flattenAndConvertCodesR4(contains, codes);
				break;
			}
			case R4B: {
				org.hl7.fhir.r4b.model.ValueSet expansionVs = (org.hl7.fhir.r4b.model.ValueSet) theExpansion;
				List<org.hl7.fhir.r4b.model.ValueSet.ValueSetExpansionContainsComponent> contains =
						expansionVs.getExpansion().getContains();
				flattenAndConvertCodesR4B(contains, codes);
				break;
			}
			case R5: {
				org.hl7.fhir.r5.model.ValueSet expansionVs = (org.hl7.fhir.r5.model.ValueSet) theExpansion;
				List<org.hl7.fhir.r5.model.ValueSet.ValueSetExpansionContainsComponent> contains =
						expansionVs.getExpansion().getContains();
				flattenAndConvertCodesR5(contains, codes);
				break;
			}
			case DSTU2_1:
			default:
				throw new IllegalArgumentException(Msg.code(700) + "Can not handle version: "
						+ myCtx.getVersion().getVersion());
		}

		String codeSystemResourceName = null;
		String codeSystemResourceVersion = null;
		String codeSystemResourceContentMode = null;
		if (codeSystemToValidateResource != null) {
			switch (getFhirVersionEnum(
					theValidationSupportContext.getRootValidationSupport().getFhirContext(),
					codeSystemToValidateResource)) {
				case DSTU2:
				case DSTU2_HL7ORG: {
					caseSensitive = true;
					break;
				}
				case DSTU3: {
					org.hl7.fhir.dstu3.model.CodeSystem systemDstu3 =
							(org.hl7.fhir.dstu3.model.CodeSystem) codeSystemToValidateResource;
					caseSensitive = systemDstu3.getCaseSensitive();
					codeSystemResourceName = systemDstu3.getName();
					codeSystemResourceVersion = systemDstu3.getVersion();
					codeSystemResourceContentMode =
							systemDstu3.getContentElement().getValueAsString();
					break;
				}
				case R4: {
					org.hl7.fhir.r4.model.CodeSystem systemR4 =
							(org.hl7.fhir.r4.model.CodeSystem) codeSystemToValidateResource;
					caseSensitive = systemR4.getCaseSensitive();
					codeSystemResourceName = systemR4.getName();
					codeSystemResourceVersion = systemR4.getVersion();
					codeSystemResourceContentMode = systemR4.getContentElement().getValueAsString();
					break;
				}
				case R4B: {
					org.hl7.fhir.r4b.model.CodeSystem systemR4B =
							(org.hl7.fhir.r4b.model.CodeSystem) codeSystemToValidateResource;
					caseSensitive = systemR4B.getCaseSensitive();
					codeSystemResourceName = systemR4B.getName();
					codeSystemResourceVersion = systemR4B.getVersion();
					codeSystemResourceContentMode =
							systemR4B.getContentElement().getValueAsString();
					break;
				}
				case R5: {
					CodeSystem systemR5 = (CodeSystem) codeSystemToValidateResource;
					caseSensitive = systemR5.getCaseSensitive();
					codeSystemResourceName = systemR5.getName();
					codeSystemResourceVersion = systemR5.getVersion();
					codeSystemResourceContentMode = systemR5.getContentElement().getValueAsString();
					break;
				}
				case DSTU2_1:
				default:
					throw new IllegalArgumentException(Msg.code(701) + "Can not handle version: "
							+ myCtx.getVersion().getVersion());
			}
		}

		String codeSystemUrlToValidate = null;
		String codeSystemVersionToValidate = null;
		if (theCodeSystemUrlAndVersionToValidate != null) {
			int versionIndex = theCodeSystemUrlAndVersionToValidate.indexOf("|");
			if (versionIndex > -1) {
				codeSystemUrlToValidate = theCodeSystemUrlAndVersionToValidate.substring(0, versionIndex);
				codeSystemVersionToValidate = theCodeSystemUrlAndVersionToValidate.substring(versionIndex + 1);
			} else {
				codeSystemUrlToValidate = theCodeSystemUrlAndVersionToValidate;
			}
		}
		CodeValidationResult valueSetResult = findCodeInExpansion(
				theCodeToValidate,
				theDisplayToValidate,
				theValueSetUrl,
				codeSystemUrlToValidate,
				codeSystemVersionToValidate,
				codeSystemResourceName,
				codeSystemResourceVersion,
				codes,
				theOptions,
				caseSensitive);
		if (valueSetResult != null) {
			codeValidationResult = valueSetResult;
		} else {
			IValidationSupport.IssueSeverity severity;
			String message;
			CodeValidationIssueCode issueCode = CodeValidationIssueCode.CODE_INVALID;
			CodeValidationIssueCoding issueCoding = CodeValidationIssueCoding.INVALID_CODE;
			if ("fragment".equals(codeSystemResourceContentMode)) {
				severity = IValidationSupport.IssueSeverity.WARNING;
				message = "Unknown code in fragment CodeSystem '"
						+ getFormattedCodeSystemAndCodeForMessage(
								theCodeSystemUrlAndVersionToValidate, theCodeToValidate)
						+ "'";
			} else {
				severity = IValidationSupport.IssueSeverity.ERROR;
				message = "Unknown code '"
						+ getFormattedCodeSystemAndCodeForMessage(
								theCodeSystemUrlAndVersionToValidate, theCodeToValidate)
						+ "'";
			}
			if (isNotBlank(theValueSetUrl)) {
				message += createInMemoryExpansionMessageSuffix(theValueSetUrl);
				issueCoding = CodeValidationIssueCoding.NOT_IN_VS;
			}

			String sourceDetails = "In-memory expansion containing " + codes.size() + " codes";
			if (!codes.isEmpty() && codes.size() < 10) {
				sourceDetails += ": "
						+ codes.stream()
								.map(t -> t.getSystem() + "#" + t.getCode())
								.collect(Collectors.joining(", "));
			}

			codeValidationResult = new CodeValidationResult()
					.setSeverity(severity)
					.setMessage(message)
					.setSourceDetails(sourceDetails)
					.addIssue(new CodeValidationIssue(message, severity, issueCode, issueCoding));
		}

		return codeValidationResult;
	}

	@Nonnull
	private static String createInMemoryExpansionMessageSuffix(String theValueSetUrl) {
		return " for in-memory expansion of ValueSet '" + theValueSetUrl + "'";
	}

	private static String getFormattedCodeSystemAndCodeForMessage(
			String theCodeSystemUrlAndVersionToValidate, String theCodeToValidate) {
		return (isNotBlank(theCodeSystemUrlAndVersionToValidate) ? theCodeSystemUrlAndVersionToValidate + "#" : "")
				+ theCodeToValidate;
	}

	private CodeValidationResult findCodeInExpansion(
			String theCodeToValidate,
			String theDisplayToValidate,
			String theValueSetUrl,
			String codeSystemUrlToValidate,
			String codeSystemVersionToValidate,
			String codeSystemResourceName,
			String codeSystemResourceVersion,
			List<FhirVersionIndependentConcept> expansionCodes,
			ConceptValidationOptions theOptions,
			boolean caseSensitive) {
		for (FhirVersionIndependentConcept nextExpansionCode : expansionCodes) {

			boolean codeMatches;
			if (caseSensitive) {
				codeMatches = defaultString(theCodeToValidate).equals(nextExpansionCode.getCode());
			} else {
				codeMatches = defaultString(theCodeToValidate).equalsIgnoreCase(nextExpansionCode.getCode());
			}
			if (codeMatches) {
				if (theOptions.isInferSystem()
						|| (nextExpansionCode.getSystem().equals(codeSystemUrlToValidate)
								&& (codeSystemVersionToValidate == null
										|| codeSystemVersionToValidate.equals(nextExpansionCode.getSystemVersion())))) {
					String csVersion = codeSystemResourceVersion;
					if (isNotBlank(nextExpansionCode.getSystemVersion())) {
						csVersion = nextExpansionCode.getSystemVersion();
					}
					if (!theOptions.isValidateDisplay()
							|| (isBlank(nextExpansionCode.getDisplay())
									|| isBlank(theDisplayToValidate)
									|| nextExpansionCode.getDisplay().equals(theDisplayToValidate))) {
						CodeValidationResult codeValidationResult = new CodeValidationResult()
								.setCode(theCodeToValidate)
								.setDisplay(nextExpansionCode.getDisplay())
								.setCodeSystemName(codeSystemResourceName)
								.setCodeSystemVersion(csVersion);
						if (isNotBlank(theValueSetUrl)) {
							populateSourceDetailsForInMemoryExpansion(theValueSetUrl, codeValidationResult);
						}
						return codeValidationResult;
					} else {
						String messageAppend = "";
						if (isNotBlank(theValueSetUrl)) {
							messageAppend = createInMemoryExpansionMessageSuffix(theValueSetUrl);
						}
						CodeValidationResult codeValidationResult = createResultForDisplayMismatch(
								myCtx,
								theCodeToValidate,
								theDisplayToValidate,
								nextExpansionCode.getDisplay(),
								codeSystemUrlToValidate,
								csVersion,
								messageAppend,
								getIssueSeverityForCodeDisplayMismatch());
						if (isNotBlank(theValueSetUrl)) {
							populateSourceDetailsForInMemoryExpansion(theValueSetUrl, codeValidationResult);
						}
						return codeValidationResult;
					}
				}
			}
		}
		return null;
	}

	@Override
	public LookupCodeResult lookupCode(
			ValidationSupportContext theValidationSupportContext, @Nonnull LookupCodeRequest theLookupCodeRequest) {
		final String code = theLookupCodeRequest.getCode();
		final String system = theLookupCodeRequest.getSystem();
		CodeValidationResult codeValidationResult = validateCode(
				theValidationSupportContext,
				new ConceptValidationOptions(),
				system,
				code,
				theLookupCodeRequest.getDisplayLanguage(),
				null);
		if (codeValidationResult == null) {
			return null;
		}
		return codeValidationResult.asLookupCodeResult(system, code);
	}

	@Override
	public boolean isCodeSystemSupported(ValidationSupportContext theValidationSupportContext, String theSystem) {
		if (isBlank(theSystem)) {
			return false;
		}

		IBaseResource cs =
				theValidationSupportContext.getRootValidationSupport().fetchCodeSystem(theSystem);

		if (!myCtx.getVersion().getVersion().isEqualOrNewerThan(FhirVersionEnum.DSTU2_1)) {
			return cs != null;
		}

		if (cs != null) {
			IPrimitiveType<?> content =
					getFhirContext().newTerser().getSingleValueOrNull(cs, "content", IPrimitiveType.class);
			return !"not-present".equals(content.getValueAsString());
		}

		return false;
	}

	@Override
	public boolean isValueSetSupported(ValidationSupportContext theValidationSupportContext, String theValueSetUrl) {
		return isNotBlank(theValueSetUrl)
				&& theValidationSupportContext.getRootValidationSupport().fetchValueSet(theValueSetUrl) != null;
	}

	private void addCodesDstu2Hl7Org(
			List<ValueSet.ConceptDefinitionComponent> theSourceList,
			List<CodeSystem.ConceptDefinitionComponent> theTargetList) {
		for (ValueSet.ConceptDefinitionComponent nextSource : theSourceList) {
			CodeSystem.ConceptDefinitionComponent targetConcept = new CodeSystem.ConceptDefinitionComponent()
					.setCode(nextSource.getCode())
					.setDisplay(nextSource.getDisplay());
			theTargetList.add(targetConcept);
			addCodesDstu2Hl7Org(nextSource.getConcept(), targetConcept.getConcept());
		}
	}

	private void addCodesDstu2(
			List<ca.uhn.fhir.model.dstu2.resource.ValueSet.CodeSystemConcept> theSourceList,
			List<CodeSystem.ConceptDefinitionComponent> theTargetList) {
		for (ca.uhn.fhir.model.dstu2.resource.ValueSet.CodeSystemConcept nextSource : theSourceList) {
			CodeSystem.ConceptDefinitionComponent targetConcept = new CodeSystem.ConceptDefinitionComponent()
					.setCode(nextSource.getCode())
					.setDisplay(nextSource.getDisplay());
			theTargetList.add(targetConcept);
			addCodesDstu2(nextSource.getConcept(), targetConcept.getConcept());
		}
	}

	@Nullable
	private ValueSetAndMessages expandValueSetR5(
			ValidationSupportContext theValidationSupportContext,
			org.hl7.fhir.r5.model.ValueSet theInput,
			@Nullable String theWantSystemUrlAndVersion,
			@Nullable String theWantCode)
			throws ExpansionCouldNotBeCompletedInternallyException {

		ValueSetAndMessages retVal = new ValueSetAndMessages();
		Set<FhirVersionIndependentConcept> concepts = new HashSet<>();

		expandValueSetR5IncludeOrExcludes(
				theValidationSupportContext,
				concepts,
				theInput.getCompose().getInclude(),
				true,
				theWantSystemUrlAndVersion,
				theWantCode,
				retVal);
		expandValueSetR5IncludeOrExcludes(
				theValidationSupportContext,
				concepts,
				theInput.getCompose().getExclude(),
				false,
				theWantSystemUrlAndVersion,
				theWantCode,
				retVal);

		org.hl7.fhir.r5.model.ValueSet vs = new org.hl7.fhir.r5.model.ValueSet();
		retVal.setValueSet(vs);
		for (FhirVersionIndependentConcept next : concepts) {
			org.hl7.fhir.r5.model.ValueSet.ValueSetExpansionContainsComponent contains =
					vs.getExpansion().addContains();
			contains.setSystem(next.getSystem());
			contains.setCode(next.getCode());
			contains.setDisplay(next.getDisplay());
			contains.setVersion(next.getSystemVersion());
		}

		return retVal;
	}

	/**
	 * Use with caution - this is not a stable API
	 *
	 * @since 5.6.0
	 */
	public void expandValueSetIncludeOrExclude(
			ValidationSupportContext theValidationSupportContext,
			Consumer<FhirVersionIndependentConcept> theConsumer,
			org.hl7.fhir.r5.model.ValueSet.ConceptSetComponent theIncludeOrExclude)
			throws ExpansionCouldNotBeCompletedInternallyException {
		expandValueSetR5IncludeOrExclude(
				theValidationSupportContext, theConsumer, null, null, theIncludeOrExclude, new ValueSetAndMessages());
	}

	private void expandValueSetR5IncludeOrExcludes(
			ValidationSupportContext theValidationSupportContext,
			Set<FhirVersionIndependentConcept> theConcepts,
			List<org.hl7.fhir.r5.model.ValueSet.ConceptSetComponent> theComposeList,
			boolean theComposeListIsInclude,
			@Nullable String theWantSystemUrlAndVersion,
			@Nullable String theWantCode,
			ValueSetAndMessages theResponseBuilder)
			throws ExpansionCouldNotBeCompletedInternallyException {
		Consumer<FhirVersionIndependentConcept> consumer = c -> {
			if (theComposeListIsInclude) {
				theConcepts.add(c);
			} else {
				theConcepts.remove(c);
			}
		};
		expandValueSetR5IncludeOrExcludes(
				theComposeListIsInclude,
				theValidationSupportContext,
				consumer,
				theComposeList,
				theWantSystemUrlAndVersion,
				theWantCode,
				theResponseBuilder);
	}

	private void expandValueSetR5IncludeOrExcludes(
			boolean theComposeListIsInclude,
			ValidationSupportContext theValidationSupportContext,
			Consumer<FhirVersionIndependentConcept> theConsumer,
			List<org.hl7.fhir.r5.model.ValueSet.ConceptSetComponent> theComposeList,
			@Nullable String theWantSystemUrlAndVersion,
			@Nullable String theWantCode,
			ValueSetAndMessages theResponseBuilder)
			throws ExpansionCouldNotBeCompletedInternallyException {
		ExpansionCouldNotBeCompletedInternallyException caughtException = null;
		if (theComposeList.isEmpty()) {
			if (theComposeListIsInclude) {
				theResponseBuilder.addMessage("Empty compose list for includes");
			}
			return;
		}
		for (org.hl7.fhir.r5.model.ValueSet.ConceptSetComponent nextInclude : theComposeList) {
			try {
				boolean outcome = expandValueSetR5IncludeOrExclude(
						theValidationSupportContext,
						theConsumer,
						theWantSystemUrlAndVersion,
						theWantCode,
						nextInclude,
						theResponseBuilder);
				if (isNotBlank(theWantCode)) {
					if (outcome) {
						return;
					}
				}
			} catch (ExpansionCouldNotBeCompletedInternallyException e) {
				if (isBlank(theWantCode)) {
					throw e;
				} else {
					caughtException = e;
				}
			}
		}
		if (caughtException != null) {
			throw caughtException;
		}
	}

	/**
	 * Returns <code>true</code> if at least one code was added
	 */
	private boolean expandValueSetR5IncludeOrExclude(
			ValidationSupportContext theValidationSupportContext,
			Consumer<FhirVersionIndependentConcept> theConsumer,
			@Nullable String theWantSystemUrlAndVersion,
			@Nullable String theWantCode,
			org.hl7.fhir.r5.model.ValueSet.ConceptSetComponent theInclude,
			ValueSetAndMessages theResponseBuilder)
			throws ExpansionCouldNotBeCompletedInternallyException {

		String wantSystemUrl = null;
		String wantSystemVersion = null;

		if (theWantSystemUrlAndVersion != null) {
			int versionIndex = theWantSystemUrlAndVersion.indexOf(OUR_PIPE_CHARACTER);
			if (versionIndex > -1) {
				wantSystemUrl = theWantSystemUrlAndVersion.substring(0, versionIndex);
				wantSystemVersion = theWantSystemUrlAndVersion.substring(versionIndex + 1);
			} else {
				wantSystemUrl = theWantSystemUrlAndVersion;
			}
		}

		String includeOrExcludeConceptSystemUrl = theInclude.getSystem();
		String includeOrExcludeConceptSystemVersion = theInclude.getVersion();

		Function<String, CodeSystem> codeSystemLoader = newCodeSystemLoader(theValidationSupportContext);
		Function<String, org.hl7.fhir.r5.model.ValueSet> valueSetLoader =
				newValueSetLoader(theValidationSupportContext);

		List<FhirVersionIndependentConcept> nextCodeList = new ArrayList<>();
		CodeSystem includeOrExcludeSystemResource = null;

		if (isNotBlank(includeOrExcludeConceptSystemUrl)) {

			includeOrExcludeConceptSystemVersion = optionallyPopulateVersionFromUrl(
					includeOrExcludeConceptSystemUrl, includeOrExcludeConceptSystemVersion);
			includeOrExcludeConceptSystemUrl = substringBefore(includeOrExcludeConceptSystemUrl, OUR_PIPE_CHARACTER);

			if (wantSystemUrl != null && !wantSystemUrl.equals(includeOrExcludeConceptSystemUrl)) {
				return false;
			}

			if (wantSystemVersion != null && !wantSystemVersion.equals(includeOrExcludeConceptSystemVersion)) {
				return false;
			}

			String loadedCodeSystemUrl;
			if (includeOrExcludeConceptSystemVersion != null) {
				loadedCodeSystemUrl =
						includeOrExcludeConceptSystemUrl + OUR_PIPE_CHARACTER + includeOrExcludeConceptSystemVersion;
			} else {
				loadedCodeSystemUrl = includeOrExcludeConceptSystemUrl;
			}

			includeOrExcludeSystemResource = codeSystemLoader.apply(loadedCodeSystemUrl);

			boolean isIncludeWithDeclaredConcepts = !theInclude.getConcept().isEmpty();

			final Set<String> wantCodes;
			if (isIncludeWithDeclaredConcepts) {
				wantCodes = theInclude.getConcept().stream()
						.map(org.hl7.fhir.r5.model.ValueSet.ConceptReferenceComponent::getCode)
						.collect(Collectors.toSet());
			} else {
				wantCodes = null;
			}

			boolean ableToHandleCode = false;
			String failureMessage = null;

			boolean isIncludeCodeSystemIgnored = includeOrExcludeSystemResource != null
					&& includeOrExcludeSystemResource.getContent() == Enumerations.CodeSystemContentMode.NOTPRESENT;

			boolean isIncludeFromSystem = isNotBlank(theInclude.getSystem())
					&& theInclude.getValueSet().isEmpty();
			boolean isIncludeWithFilter = !theInclude.getFilter().isEmpty();

			// if we can’t load the CS and we’re configured to ignore it...
			if (isIncludeCodeSystemIgnored && !theInclude.getFilter().isEmpty()) {
				// We can’t apply any structural (ISA/DESCENDENT-OF/etc..) filters if the CS is absent → fail
				String msg = "Unable to expand ValueSet: cannot apply filters '" + theInclude.getFilter()
						+ "' because CodeSystem '" + theInclude.getSystem()
						+ "' is ignored/not-present";

				throw new ExpansionCouldNotBeCompletedInternallyException(
						Msg.code(2646) + msg,
						new CodeValidationIssue(
								msg,
								IssueSeverity.ERROR,
								CodeValidationIssueCode.NOT_FOUND,
								CodeValidationIssueCoding.NOT_FOUND));
			}

			if (includeOrExcludeSystemResource == null || isIncludeCodeSystemIgnored) {

				if (theWantCode != null) {
					if (theValidationSupportContext
							.getRootValidationSupport()
							.isCodeSystemSupported(theValidationSupportContext, includeOrExcludeConceptSystemUrl)) {
						LookupCodeResult lookup = theValidationSupportContext
								.getRootValidationSupport()
								.lookupCode(
										theValidationSupportContext,
										new LookupCodeRequest(includeOrExcludeConceptSystemUrl, theWantCode));
						if (lookup != null) {
							ableToHandleCode = true;
							if (lookup.isFound()) {
								CodeSystem.ConceptDefinitionComponent conceptDefinition =
										new CodeSystem.ConceptDefinitionComponent()
												.addConcept()
												.setCode(theWantCode)
												.setDisplay(lookup.getCodeDisplay());
								List<CodeSystem.ConceptDefinitionComponent> codesList =
										Collections.singletonList(conceptDefinition);
								addCodes(
										includeOrExcludeConceptSystemUrl,
										includeOrExcludeConceptSystemVersion,
										codesList,
										nextCodeList,
										wantCodes);
							}
						}
					} else {

						/*
						 * If we're doing an expansion specifically looking for a single code, that means we're validating that code.
						 * In the case where we have a ValueSet that explicitly enumerates a collection of codes
						 * (via ValueSet.compose.include.code) in a code system that is unknown we'll assume the code is valid
						 * even if we can't find the CodeSystem. This is a compromise obviously, since it would be ideal for
						 * CodeSystems to always be known, but realistically there are always going to be CodeSystems that
						 * can't be supplied because of copyright issues, or because they are grammar based. Allowing a VS to
						 * enumerate a set of good codes for them is a nice compromise there.
						 */
						if (Objects.equals(theInclude.getSystem(), theWantSystemUrlAndVersion)) {
							Optional<org.hl7.fhir.r5.model.ValueSet.ConceptReferenceComponent>
									matchingEnumeratedConcept = theInclude.getConcept().stream()
											.filter(t -> Objects.equals(t.getCode(), theWantCode))
											.findFirst();

							// If the ValueSet.compose.include has no individual concepts in it, and
							// we can't find the actual referenced CodeSystem, we have no choice
							// but to fail
							if (isIncludeWithDeclaredConcepts) {
								ableToHandleCode = true;
							} else {
								failureMessage = getFailureMessageForMissingOrUnusableCodeSystem(
										includeOrExcludeSystemResource, loadedCodeSystemUrl);
							}

							if (matchingEnumeratedConcept.isPresent()) {
								CodeSystem.ConceptDefinitionComponent conceptDefinition =
										new CodeSystem.ConceptDefinitionComponent()
												.addConcept()
												.setCode(theWantCode)
												.setDisplay(matchingEnumeratedConcept
														.get()
														.getDisplay());
								List<CodeSystem.ConceptDefinitionComponent> codesList =
										Collections.singletonList(conceptDefinition);
								addCodes(
										includeOrExcludeConceptSystemUrl,
										includeOrExcludeConceptSystemVersion,
										codesList,
										nextCodeList,
										wantCodes);
							}
						}
					}
				} else {
					if (isIncludeFromSystem && !isIncludeWithFilter) {
						if (isIncludeWithDeclaredConcepts) {
							theInclude.getConcept().stream()
									.map(t -> new FhirVersionIndependentConcept(
											theInclude.getSystem(),
											t.getCode(),
											t.getDisplay(),
											theInclude.getVersion()))
									.forEach(nextCodeList::add);
							ableToHandleCode = true;
						} else if (isIncludeCodeSystemIgnored) {
							ableToHandleCode = true;
						}
					}

					if (!ableToHandleCode) {
						failureMessage = getFailureMessageForMissingOrUnusableCodeSystem(
								includeOrExcludeSystemResource, loadedCodeSystemUrl);
					}
				}

			} else {
				ableToHandleCode = true;
			}

			if (!ableToHandleCode) {
				if (failureMessage == null) {
					if (includeOrExcludeSystemResource == null) {
						failureMessage = getFailureMessageForMissingOrUnusableCodeSystem(
								includeOrExcludeSystemResource, loadedCodeSystemUrl);
					} else {
						failureMessage = "Unable to expand value set";
					}
				}

				throw new ExpansionCouldNotBeCompletedInternallyException(
						Msg.code(702) + failureMessage,
						new CodeValidationIssue(
								failureMessage,
								IssueSeverity.ERROR,
								CodeValidationIssueCode.NOT_FOUND,
								CodeValidationIssueCoding.NOT_FOUND));
			}

			if (includeOrExcludeSystemResource != null
					&& includeOrExcludeSystemResource.getContent() != Enumerations.CodeSystemContentMode.NOTPRESENT) {
				addCodes(
						includeOrExcludeConceptSystemUrl,
						includeOrExcludeConceptSystemVersion,
						includeOrExcludeSystemResource.getConcept(),
						nextCodeList,
						wantCodes);
			}
		}

		for (CanonicalType nextValueSetInclude : theInclude.getValueSet()) {
			org.hl7.fhir.r5.model.ValueSet vs = valueSetLoader.apply(nextValueSetInclude.getValueAsString());
			if (vs != null) {
				org.hl7.fhir.r5.model.ValueSet subExpansion = expandValueSetR5(
								theValidationSupportContext, vs, theWantSystemUrlAndVersion, theWantCode)
						.getValueSet();
				if (subExpansion == null) {
					String theMessage = "Failed to expand ValueSet: " + nextValueSetInclude.getValueAsString();
					throw new ExpansionCouldNotBeCompletedInternallyException(
							Msg.code(703) + theMessage,
							new CodeValidationIssue(
									theMessage,
									IssueSeverity.ERROR,
									CodeValidationIssueCode.INVALID,
									CodeValidationIssueCoding.VS_INVALID));
				}
				for (org.hl7.fhir.r5.model.ValueSet.ValueSetExpansionContainsComponent next :
						subExpansion.getExpansion().getContains()) {
					nextCodeList.add(new FhirVersionIndependentConcept(
							next.getSystem(), next.getCode(), next.getDisplay(), next.getVersion()));
				}
			}
		}

		boolean retVal = false;
		ValueSetExpansionFilterContext valueSetExpansionFilterContext =
				new ValueSetExpansionFilterContext(includeOrExcludeSystemResource, theInclude.getFilter());

		for (FhirVersionIndependentConcept next : nextCodeList) {
			if (includeOrExcludeSystemResource != null && theWantCode != null) {
				boolean matches = includeOrExcludeSystemResource.getCaseSensitive()
						? theWantCode.equals(next.getCode())
						: theWantCode.equalsIgnoreCase(next.getCode());

				if (!matches) {
					continue;
				}
			}

			if (!valueSetExpansionFilterContext.isFiltered(next)) {
				theConsumer.accept(next);
				retVal = true;
			}
		}

		return retVal;
	}

	private Function<String, org.hl7.fhir.r5.model.ValueSet> newValueSetLoader(
			ValidationSupportContext theValidationSupportContext) {
		return t -> {
			IBaseResource valueSet =
					theValidationSupportContext.getRootValidationSupport().fetchValueSet(t);
			return myVersionCanonicalizer.valueSetToValidatorCanonical(valueSet);
		};
	}

	private Function<String, CodeSystem> newCodeSystemLoader(ValidationSupportContext theValidationSupportContext) {
		FhirVersionEnum version = myCtx.getVersion().getVersion();
		if (FhirVersionEnum.DSTU2.equals(version) || FhirVersionEnum.DSTU2_HL7ORG.equals(version)) {
			return t -> {
				IBaseResource codeSystem =
						theValidationSupportContext.getRootValidationSupport().fetchCodeSystem(t);
				CodeSystem retVal = null;
				if (codeSystem != null) {
					retVal = new CodeSystem();
					if (codeSystem instanceof ca.uhn.fhir.model.dstu2.resource.ValueSet codeSystemCasted) {
						retVal.setUrl(codeSystemCasted.getUrl());
						addCodesDstu2(codeSystemCasted.getCodeSystem().getConcept(), retVal.getConcept());
					} else {
						org.hl7.fhir.dstu2.model.ValueSet codeSystemCasted =
								(org.hl7.fhir.dstu2.model.ValueSet) codeSystem;
						retVal.setUrl(codeSystemCasted.getUrl());
						addCodesDstu2Hl7Org(codeSystemCasted.getCodeSystem().getConcept(), retVal.getConcept());
					}
				}
				return retVal;
			};
		} else {
			return t -> {
				IBaseResource codeSystem =
						theValidationSupportContext.getRootValidationSupport().fetchCodeSystem(t);
				return myVersionCanonicalizer.codeSystemToValidatorCanonical(codeSystem);
			};
		}
	}

	private String getFailureMessageForMissingOrUnusableCodeSystem(
			CodeSystem includeOrExcludeSystemResource, String loadedCodeSystemUrl) {
		String failureMessage;
		if (includeOrExcludeSystemResource == null) {
			failureMessage = "Unable to expand ValueSet because CodeSystem could not be found: " + loadedCodeSystemUrl;
		} else {
			assert includeOrExcludeSystemResource.getContent() == Enumerations.CodeSystemContentMode.NOTPRESENT;
			failureMessage =
					"Unable to expand ValueSet because CodeSystem has CodeSystem.content=not-present but contents were not found: "
							+ loadedCodeSystemUrl;
		}
		return failureMessage;
	}

	private void addCodes(
			String theCodeSystemUrl,
			String theCodeSystemVersion,
			List<CodeSystem.ConceptDefinitionComponent> theSource,
			List<FhirVersionIndependentConcept> theTarget,
			Set<String> theCodeFilter) {
		for (CodeSystem.ConceptDefinitionComponent next : theSource) {
			if (isNotBlank(next.getCode())) {
				if (theCodeFilter == null || theCodeFilter.contains(next.getCode())) {
					theTarget.add(new FhirVersionIndependentConcept(
							theCodeSystemUrl, next.getCode(), next.getDisplay(), theCodeSystemVersion));
				}
			}
			addCodes(theCodeSystemUrl, theCodeSystemVersion, next.getConcept(), theTarget, theCodeFilter);
		}
	}

	private String optionallyPopulateVersionFromUrl(String theSystemUrl, String theVersion) {
		if (contains(theSystemUrl, OUR_PIPE_CHARACTER) && isBlank(theVersion)) {
			theVersion = substringAfter(theSystemUrl, OUR_PIPE_CHARACTER);
		}
		return theVersion;
	}

	private static void populateSourceDetailsForInMemoryExpansion(
			String theValueSetUrl, CodeValidationResult codeValidationResult) {
		codeValidationResult.setSourceDetails(
				"Code was validated against in-memory expansion of ValueSet: " + theValueSetUrl);
	}

	public static CodeValidationResult createResultForDisplayMismatch(
			FhirContext theFhirContext,
			String theCode,
			String theDisplay,
			String theExpectedDisplay,
			String theCodeSystem,
			String theCodeSystemVersion,
			IssueSeverity theIssueSeverityForCodeDisplayMismatch) {
		return createResultForDisplayMismatch(
				theFhirContext,
				theCode,
				theDisplay,
				theExpectedDisplay,
				theCodeSystem,
				theCodeSystemVersion,
				"",
				theIssueSeverityForCodeDisplayMismatch);
	}

	private static CodeValidationResult createResultForDisplayMismatch(
			FhirContext theFhirContext,
			String theCode,
			String theDisplay,
			String theExpectedDisplay,
			String theCodeSystem,
			String theCodeSystemVersion,
			String theMessageAppend,
			IssueSeverity theIssueSeverityForCodeDisplayMismatch) {

		String message;
		IssueSeverity issueSeverity = theIssueSeverityForCodeDisplayMismatch;
		if (issueSeverity == IssueSeverity.INFORMATION) {
			message = null;
			issueSeverity = null;
		} else {
			message = theFhirContext
							.getLocalizer()
							.getMessage(
									InMemoryTerminologyServerValidationSupport.class,
									"displayMismatch",
									theDisplay,
									theExpectedDisplay,
									theCodeSystem,
									theCode)
					+ theMessageAppend;
		}
		CodeValidationResult codeValidationResult = new CodeValidationResult()
				.setSeverity(issueSeverity)
				.setMessage(message)
				.setCode(theCode)
				.setCodeSystemVersion(theCodeSystemVersion)
				.setDisplay(theExpectedDisplay);
		if (issueSeverity != null) {
			codeValidationResult.setIssues(Collections.singletonList(new CodeValidationIssue(
					message,
					theIssueSeverityForCodeDisplayMismatch,
					CodeValidationIssueCode.INVALID,
					CodeValidationIssueCoding.INVALID_DISPLAY)));
		}

		return codeValidationResult;
	}

	private static void flattenAndConvertCodesDstu2(
			List<ca.uhn.fhir.model.dstu2.resource.ValueSet.ExpansionContains> theInput,
			List<FhirVersionIndependentConcept> theFhirVersionIndependentConcepts) {
		for (ca.uhn.fhir.model.dstu2.resource.ValueSet.ExpansionContains next : theInput) {
			theFhirVersionIndependentConcepts.add(
					new FhirVersionIndependentConcept(next.getSystem(), next.getCode(), next.getDisplay()));
			flattenAndConvertCodesDstu2(next.getContains(), theFhirVersionIndependentConcepts);
		}
	}

	private static void flattenAndConvertCodesDstu2Hl7Org(
			List<org.hl7.fhir.dstu2.model.ValueSet.ValueSetExpansionContainsComponent> theInput,
			List<FhirVersionIndependentConcept> theFhirVersionIndependentConcepts) {
		for (org.hl7.fhir.dstu2.model.ValueSet.ValueSetExpansionContainsComponent next : theInput) {
			theFhirVersionIndependentConcepts.add(
					new FhirVersionIndependentConcept(next.getSystem(), next.getCode(), next.getDisplay()));
			flattenAndConvertCodesDstu2Hl7Org(next.getContains(), theFhirVersionIndependentConcepts);
		}
	}

	private static void flattenAndConvertCodesDstu3(
			List<org.hl7.fhir.dstu3.model.ValueSet.ValueSetExpansionContainsComponent> theInput,
			List<FhirVersionIndependentConcept> theFhirVersionIndependentConcepts) {
		for (org.hl7.fhir.dstu3.model.ValueSet.ValueSetExpansionContainsComponent next : theInput) {
			theFhirVersionIndependentConcepts.add(new FhirVersionIndependentConcept(
					next.getSystem(), next.getCode(), next.getDisplay(), next.getVersion()));
			flattenAndConvertCodesDstu3(next.getContains(), theFhirVersionIndependentConcepts);
		}
	}

	private static void flattenAndConvertCodesR4(
			List<org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionContainsComponent> theInput,
			List<FhirVersionIndependentConcept> theFhirVersionIndependentConcepts) {
		for (org.hl7.fhir.r4.model.ValueSet.ValueSetExpansionContainsComponent next : theInput) {
			theFhirVersionIndependentConcepts.add(new FhirVersionIndependentConcept(
					next.getSystem(), next.getCode(), next.getDisplay(), next.getVersion()));
			flattenAndConvertCodesR4(next.getContains(), theFhirVersionIndependentConcepts);
		}
	}

	private static void flattenAndConvertCodesR4B(
			List<org.hl7.fhir.r4b.model.ValueSet.ValueSetExpansionContainsComponent> theInput,
			List<FhirVersionIndependentConcept> theFhirVersionIndependentConcepts) {
		for (org.hl7.fhir.r4b.model.ValueSet.ValueSetExpansionContainsComponent next : theInput) {
			theFhirVersionIndependentConcepts.add(new FhirVersionIndependentConcept(
					next.getSystem(), next.getCode(), next.getDisplay(), next.getVersion()));
			flattenAndConvertCodesR4B(next.getContains(), theFhirVersionIndependentConcepts);
		}
	}

	private static void flattenAndConvertCodesR5(
			List<org.hl7.fhir.r5.model.ValueSet.ValueSetExpansionContainsComponent> theInput,
			List<FhirVersionIndependentConcept> theFhirVersionIndependentConcepts) {
		for (org.hl7.fhir.r5.model.ValueSet.ValueSetExpansionContainsComponent next : theInput) {
			theFhirVersionIndependentConcepts.add(new FhirVersionIndependentConcept(
					next.getSystem(), next.getCode(), next.getDisplay(), next.getVersion()));
			flattenAndConvertCodesR5(next.getContains(), theFhirVersionIndependentConcepts);
		}
	}

	public static class ExpansionCouldNotBeCompletedInternallyException extends Exception {

		private static final long serialVersionUID = -2226561628771483085L;
		private final CodeValidationIssue myCodeValidationIssue;

		public ExpansionCouldNotBeCompletedInternallyException(
				String theMessage, CodeValidationIssue theCodeValidationIssue) {
			super(theMessage);
			myCodeValidationIssue = theCodeValidationIssue;
		}

		public CodeValidationIssue getCodeValidationIssue() {
			return myCodeValidationIssue;
		}
	}

	private static class ValueSetAndMessages {

		private org.hl7.fhir.r5.model.ValueSet myValueSet;
		private List<String> myMessages = new ArrayList<>();

		public void setValueSet(org.hl7.fhir.r5.model.ValueSet theValueSet) {
			myValueSet = theValueSet;
		}

		public void addMessage(String theMessage) {
			myMessages.add(theMessage);
		}

		public org.hl7.fhir.r5.model.ValueSet getValueSet() {
			return myValueSet;
		}

		public List<String> getMessages() {
			return myMessages;
		}
	}
}
