package ca.uhn.fhir.jpa.dao;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.BaseTag;
import ca.uhn.fhir.jpa.model.entity.ResourceHistoryTable;
import ca.uhn.fhir.model.primitive.IdDt;
import org.hl7.fhir.r4.hapi.ctx.FhirR4;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class JpaStorageResourceParserTest {

    @Mock
    private FhirContext myFhirContext;

	@Mock
	ResourceHistoryTable myEntity;

	@SuppressWarnings("unused") // injected
	@Spy
	PartitionSettings myPartitionSettings = new PartitionSettings();

	@InjectMocks
    private final JpaStorageResourceParser jpaStorageResourceParser = new JpaStorageResourceParser();

   @Test
    public void testPopulateResourceMeta_doesNotRemoveTags_whenTagListIsEmpty() {
       Mockito.when(myFhirContext.getVersion()).thenReturn(new FhirR4());
       Mockito.when(myEntity.getIdDt()).thenReturn(new IdDt("Patient/test-patient/_history/1"));

       Coding coding = new Coding("system", "code", "display");
       List<BaseTag> tagList = Collections.emptyList();
       boolean forHistoryOperation = false;
       long version = 1L;
       Patient resourceTarget = new Patient();
       resourceTarget.getMeta().addTag(coding);

	   Patient actualResult = jpaStorageResourceParser
               .populateResourceMetadata(myEntity, forHistoryOperation, tagList, version, resourceTarget);

       List<Coding> actualTagList = actualResult.getMeta().getTag();
       assertThat(actualTagList).hasSize(1);
	   assertEquals(coding, actualTagList.get(0));
    }
}
