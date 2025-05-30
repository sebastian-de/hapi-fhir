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
package ca.uhn.fhir.jpa.graphql;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.jpa.api.IDaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import ca.uhn.fhir.util.StringUtil;
import ca.uhn.fhir.util.VersionUtil;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonSerializer;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.language.InterfaceTypeDefinition;
import graphql.scalar.GraphqlStringCoercing;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.TypeResolver;
import graphql.schema.TypeResolverProxy;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.TypeRuntimeWiring;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.commons.io.output.StringBuilderWriter;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.common.hapi.validation.validator.WorkerContextValidationSupportAdapter;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.SearchParameter;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.utils.GraphQLSchemaGenerator;
import org.hl7.fhir.utilities.graphql.IGraphQLStorageServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GraphQLProviderWithIntrospection extends GraphQLProvider {

	private static final Logger ourLog = LoggerFactory.getLogger(GraphQLProviderWithIntrospection.class);
	private final Supplier<GraphQLSchemaGenerator> myGenerator;
	private final ISearchParamRegistry mySearchParamRegistry;
	private final WorkerContextValidationSupportAdapter myContext;
	private final IDaoRegistry myDaoRegistry;
	private final Gson myGson;

	/**
	 * Constructor
	 */
	public GraphQLProviderWithIntrospection(
			FhirContext theFhirContext,
			IValidationSupport theValidationSupport,
			IGraphQLStorageServices theIGraphQLStorageServices,
			ISearchParamRegistry theSearchParamRegistry,
			IDaoRegistry theDaoRegistry) {
		super(theFhirContext, theValidationSupport, theIGraphQLStorageServices);

		mySearchParamRegistry = theSearchParamRegistry;
		myDaoRegistry = theDaoRegistry;

		myContext = WorkerContextValidationSupportAdapter.newVersionSpecificWorkerContextWrapper(theValidationSupport);

		GsonBuilder gsonBuilder = new GsonBuilder();
		gsonBuilder.registerTypeAdapter(Collections.emptyList().getClass(), (JsonSerializer<Object>)
				(src, typeOfSrc, context) -> new JsonArray());
		myGson = gsonBuilder.create();

		// Lazy-load this because it's expensive, but more importantly because it makes a bunch of
		// calls for StructureDefinitions and other such things during startup so we want to be sure
		// that everything else is initialized first
		myGenerator = Suppliers.memoize(() -> new GraphQLSchemaGenerator(myContext, VersionUtil.getVersion()));
	}

	@Override
	public String processGraphQlGetRequest(ServletRequestDetails theRequestDetails, IIdType theId, String theQueryUrl) {
		return super.processGraphQlGetRequest(theRequestDetails, theId, theQueryUrl);
	}

	@Override
	public String processGraphQlPostRequest(
			ServletRequestDetails theServletRequestDetails,
			RequestDetails theRequestDetails,
			IIdType theId,
			String theQueryBody) {
		if (theQueryBody.contains("__schema")) {
			EnumSet<GraphQLSchemaGenerator.FHIROperationType> operations;
			if (theId != null) {
				throw new InvalidRequestException(
						Msg.code(2035)
								+ "GraphQL introspection not supported at instance level. Please try at server- or instance- level.");
			}

			operations = EnumSet.of(
					GraphQLSchemaGenerator.FHIROperationType.READ, GraphQLSchemaGenerator.FHIROperationType.SEARCH);

			Collection<String> resourceTypes;
			if (theRequestDetails.getResourceName() != null) {
				resourceTypes = Collections.singleton(theRequestDetails.getResourceName());
			} else {
				resourceTypes = new HashSet<>();
				for (String next : myContext.getResourceNames()) {
					if (myDaoRegistry.isResourceTypeSupported(next)) {
						resourceTypes.add(next);
					}
				}
				resourceTypes = resourceTypes.stream().sorted().collect(Collectors.toList());
			}

			return generateSchema(theQueryBody, resourceTypes, operations);
		} else {
			return super.processGraphQlPostRequest(theServletRequestDetails, theRequestDetails, theId, theQueryBody);
		}
	}

	private String generateSchema(
			String theQueryBody,
			Collection<String> theResourceTypes,
			EnumSet<GraphQLSchemaGenerator.FHIROperationType> theOperations) {

		GraphQLSchemaGenerator generator = myGenerator.get();

		final StringBuilder schemaBuilder = new StringBuilder();
		try (Writer writer = new StringBuilderWriter(schemaBuilder)) {

			// Generate FHIR base types schemas
			generator.generateTypes(writer, theOperations);

			// Fix up a few things that are missing from the generated schema
			writer.append("\ninterface Element {")
					.append("\n  id: ID")
					.append("\n}")
					.append("\n");

			// Generate schemas for the resource types
			for (String nextResourceType : theResourceTypes) {
				StructureDefinition sd = fetchStructureDefinition(nextResourceType);
				List<SearchParameter> parameters = toR5SearchParams(mySearchParamRegistry
						.getActiveSearchParams(
								nextResourceType, ISearchParamRegistry.SearchParamLookupContextEnum.SEARCH)
						.values());
				generator.generateResource(writer, sd, parameters, theOperations);
			}

			// Generate queries
			writer.append("\ntype Query {");
			for (String nextResourceType : theResourceTypes) {
				if (theOperations.contains(GraphQLSchemaGenerator.FHIROperationType.READ)) {
					writer.append("\n  ")
							.append(nextResourceType)
							.append("(id: String): ")
							.append(nextResourceType)
							.append("\n");
				}
				if (theOperations.contains(GraphQLSchemaGenerator.FHIROperationType.SEARCH)) {
					List<SearchParameter> parameters = toR5SearchParams(mySearchParamRegistry
							.getActiveSearchParams(
									nextResourceType, ISearchParamRegistry.SearchParamLookupContextEnum.SEARCH)
							.values());
					generator.generateListAccessQuery(writer, parameters, nextResourceType);
					generator.generateConnectionAccessQuery(writer, parameters, nextResourceType);
				}
			}
			writer.append("\n}");

			writer.flush();
		} catch (IOException e) {
			throw new InternalErrorException(Msg.code(2036) + e.getMessage(), e);
		}

		String schema = schemaBuilder
				.toString()
				// Use UNIX line endings in the output
				.replace("\r", "")
				// Make sure we consistently use the same datatype for all IDs - The
				// corelib GraphQL generator uses "ID" sometimes and "String" in others
				// and the GraphQL tooling doesn't like that
				.replace(" id: ID\n", " id: String\n");

		// Set these to INFO if you're testing, then set back before committing
		ourLog.debug("Schema generated: {} chars", schema.length());
		ourLog.atDebug()
				.setMessage("Schema generated:\n{}")
				.addArgument(() -> StringUtil.prependLineNumbers(schema))
				.log();

		SchemaParser schemaParser = new SchemaParser();
		TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(schema);

		SchemaGenerator schemaGenerator = new SchemaGenerator();
		RuntimeWiring.Builder runtimeWiringBuilder = RuntimeWiring.newRuntimeWiring();
		for (String next : typeDefinitionRegistry.scalars().keySet()) {
			if (Character.isUpperCase(next.charAt(0))) {
				// Skip GraphQL built-in types
				continue;
			}
			runtimeWiringBuilder.scalar(new GraphQLScalarType.Builder()
					.name(next)
					.coercing(new GraphqlStringCoercing())
					.build());
		}

		for (InterfaceTypeDefinition next : typeDefinitionRegistry.getTypes(InterfaceTypeDefinition.class)) {
			TypeResolver resolver = new TypeResolverProxy();
			TypeRuntimeWiring wiring = TypeRuntimeWiring.newTypeWiring(next.getName())
					.typeResolver(resolver)
					.build();
			runtimeWiringBuilder.type(wiring);
		}

		RuntimeWiring runtimeWiring = runtimeWiringBuilder.build();
		GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);

		GraphQL build = GraphQL.newGraphQL(graphQLSchema).build();
		ExecutionResult executionResult = build.execute(theQueryBody);

		Map<String, Object> data = executionResult.toSpecification();
		return myGson.toJson(data);
	}

	@Nonnull
	private List<SearchParameter> toR5SearchParams(Collection<RuntimeSearchParam> searchParams) {
		List<SearchParameter> parameters = new ArrayList<>();
		for (RuntimeSearchParam next : searchParams) {
			SearchParameter sp = toR5SearchParam(next);
			if (sp != null) {
				parameters.add(sp);
			}
		}
		return parameters;
	}

	@Nullable
	private SearchParameter toR5SearchParam(RuntimeSearchParam next) {
		SearchParameter sp = new SearchParameter();
		sp.setUrl(next.getUri());
		sp.setCode(next.getName());
		sp.setName(next.getName());

		switch (next.getParamType()) {
			case NUMBER:
				sp.setType(Enumerations.SearchParamType.NUMBER);
				break;
			case DATE:
				sp.setType(Enumerations.SearchParamType.DATE);
				break;
			case STRING:
				sp.setType(Enumerations.SearchParamType.STRING);
				break;
			case TOKEN:
				sp.setType(Enumerations.SearchParamType.TOKEN);
				break;
			case REFERENCE:
				sp.setType(Enumerations.SearchParamType.REFERENCE);
				break;
			case COMPOSITE:
				sp.setType(Enumerations.SearchParamType.COMPOSITE);
				break;
			case QUANTITY:
				sp.setType(Enumerations.SearchParamType.QUANTITY);
				break;
			case URI:
				sp.setType(Enumerations.SearchParamType.URI);
				break;
			case HAS:
			case SPECIAL:
			default:
				return null;
		}
		return sp;
	}

	@Nonnull
	private StructureDefinition fetchStructureDefinition(String resourceName) {
		StructureDefinition retVal = myContext.fetchResource(
				StructureDefinition.class, "http://hl7.org/fhir/StructureDefinition/" + resourceName);
		Validate.notNull(retVal);
		return retVal;
	}
}
