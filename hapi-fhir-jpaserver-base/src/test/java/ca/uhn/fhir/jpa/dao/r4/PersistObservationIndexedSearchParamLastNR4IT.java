package ca.uhn.fhir.jpa.dao.r4;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.config.TestR4ConfigWithElasticsearchClient;
import ca.uhn.fhir.jpa.dao.data.IObservationIndexedCodeCodeableConceptSearchParamDao;
import ca.uhn.fhir.jpa.dao.lastn.ObservationLastNIndexPersistSvc;
import ca.uhn.fhir.jpa.dao.lastn.entity.ObservationIndexedCodeCodeableConceptEntity;
import ca.uhn.fhir.jpa.dao.lastn.entity.ObservationIndexedSearchParamLastNEntity;
import ca.uhn.fhir.jpa.dao.data.IObservationIndexedSearchParamLastNDao;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.search.lastn.ElasticsearchSvcImpl;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.param.*;
import com.google.common.base.Charsets;
import org.apache.commons.io.IOUtils;
import org.hl7.fhir.r4.model.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {TestR4ConfigWithElasticsearchClient.class})
public class PersistObservationIndexedSearchParamLastNR4IT {

	@Autowired
	IObservationIndexedSearchParamLastNDao myResourceIndexedObservationLastNDao;

	@Autowired
	IObservationIndexedCodeCodeableConceptSearchParamDao myCodeableConceptIndexedSearchParamNormalizedDao;

	@Autowired
	private ElasticsearchSvcImpl elasticsearchSvc;

	@Autowired
	private IFhirSystemDao<Bundle, Meta> myDao;

	@PersistenceContext(type = PersistenceContextType.TRANSACTION)
	protected EntityManager myEntityManager;

	@Autowired
	ObservationLastNIndexPersistSvc testObservationPersist;

	@Before
	public void before() {

		myResourceIndexedObservationLastNDao.deleteAll();
		myCodeableConceptIndexedSearchParamNormalizedDao.deleteAll();

	}

	private final String SINGLE_SUBJECT_ID = "4567";
	private final String SINGLE_OBSERVATION_PID = "123";
	private final Date SINGLE_EFFECTIVEDTM = new Date();
	private final String SINGLE_OBSERVATION_CODE_TEXT = "Test Codeable Concept Field for Code";

	private final String CATEGORYFIRSTCODINGSYSTEM = "http://mycodes.org/fhir/observation-category";
	private final String FIRSTCATEGORYFIRSTCODINGCODE = "test-heart-rate";

	private final String CODEFIRSTCODINGSYSTEM = "http://mycodes.org/fhir/observation-code";
	private final String CODEFIRSTCODINGCODE = "test-code";

	private ReferenceAndListParam multiSubjectParams = null;

	@Test
	public void testIndexObservationSingle() {
		indexSingleObservation();
		List<ObservationIndexedSearchParamLastNEntity> persistedObservationEntities = myResourceIndexedObservationLastNDao.findAll();
		assertEquals(1, persistedObservationEntities.size());
		ObservationIndexedSearchParamLastNEntity persistedObservationEntity = persistedObservationEntities.get(0);
		assertEquals(SINGLE_SUBJECT_ID, persistedObservationEntity.getSubject());
		assertEquals(SINGLE_OBSERVATION_PID, persistedObservationEntity.getIdentifier());
		assertEquals(SINGLE_EFFECTIVEDTM, persistedObservationEntity.getEffectiveDtm());

		String observationCodeNormalizedId = persistedObservationEntity.getCodeNormalizedId();

		List<ObservationIndexedCodeCodeableConceptEntity> persistedObservationCodes = myCodeableConceptIndexedSearchParamNormalizedDao.findAll();
		assertEquals(1, persistedObservationCodes.size());
		ObservationIndexedCodeCodeableConceptEntity persistedObservationCode = persistedObservationCodes.get(0);
		assertEquals(observationCodeNormalizedId, persistedObservationCode.getCodeableConceptId());
		assertEquals(SINGLE_OBSERVATION_CODE_TEXT, persistedObservationCode.getCodeableConceptText());

		SearchParameterMap searchParameterMap = new SearchParameterMap();
		ReferenceParam subjectParam = new ReferenceParam("Patient", "", SINGLE_SUBJECT_ID);
		searchParameterMap.add(Observation.SP_SUBJECT, new ReferenceAndListParam().addAnd(new ReferenceOrListParam().addOr(subjectParam)));
		TokenParam categoryParam = new TokenParam(CATEGORYFIRSTCODINGSYSTEM, FIRSTCATEGORYFIRSTCODINGCODE);
		searchParameterMap.add(Observation.SP_CATEGORY, new TokenAndListParam().addAnd(new TokenOrListParam().addOr(categoryParam)));
		TokenParam codeParam = new TokenParam(CODEFIRSTCODINGSYSTEM, CODEFIRSTCODINGCODE);
		searchParameterMap.add(Observation.SP_CODE, new TokenAndListParam().addAnd(new TokenOrListParam().addOr(codeParam)));

		List<String> observationIdsOnly = elasticsearchSvc.executeLastN(searchParameterMap, 3, 100);

		assertEquals(1, observationIdsOnly.size());
		assertEquals(SINGLE_OBSERVATION_PID, observationIdsOnly.get(0));
	}

	private void indexSingleObservation() {

		Observation myObservation = new Observation();
		IdType observationID = new IdType("Observation", SINGLE_OBSERVATION_PID, "1");
		myObservation.setId(observationID);
		Reference subjectId = new Reference(SINGLE_SUBJECT_ID);
		myObservation.setSubject(subjectId);
		myObservation.setEffective(new DateTimeType(SINGLE_EFFECTIVEDTM));

		myObservation.setCategory(getCategoryCode());

		myObservation.setCode(getObservationCode());

		//myObservationLastNIndexPersistR4Svc.indexObservation(myObservation);
		testObservationPersist.indexObservation(myObservation);

	}

	private List<CodeableConcept> getCategoryCode() {
		// Add three CodeableConcepts for category
		List<CodeableConcept> categoryConcepts = new ArrayList<>();
		// Create three codings and first category CodeableConcept
		List<Coding> category1 = new ArrayList<>();
		CodeableConcept categoryCodeableConcept1 = new CodeableConcept().setText("Test Codeable Concept Field for first category");
		category1.add(new Coding(CATEGORYFIRSTCODINGSYSTEM, FIRSTCATEGORYFIRSTCODINGCODE, "test-heart-rate display"));
		category1.add(new Coding("http://myalternatecodes.org/fhir/observation-category", "test-alt-heart-rate", "test-alt-heart-rate display"));
		category1.add(new Coding("http://mysecondaltcodes.org/fhir/observation-category", "test-2nd-alt-heart-rate", "test-2nd-alt-heart-rate display"));
		categoryCodeableConcept1.setCoding(category1);
		categoryConcepts.add(categoryCodeableConcept1);
		// Create three codings and second category CodeableConcept
		List<Coding> category2 = new ArrayList<>();
		CodeableConcept categoryCodeableConcept2 = new CodeableConcept().setText("Test Codeable Concept Field for for second category");
		category2.add(new Coding(CATEGORYFIRSTCODINGSYSTEM, "test-vital-signs", "test-vital-signs display"));
		category2.add(new Coding("http://myalternatecodes.org/fhir/observation-category", "test-alt-vitals", "test-alt-vitals display"));
		category2.add(new Coding("http://mysecondaltcodes.org/fhir/observation-category", "test-2nd-alt-vitals", "test-2nd-alt-vitals display"));
		categoryCodeableConcept2.setCoding(category2);
		categoryConcepts.add(categoryCodeableConcept2);
		// Create three codings and third category CodeableConcept
		List<Coding> category3 = new ArrayList<>();
		CodeableConcept categoryCodeableConcept3 = new CodeableConcept().setText("Test Codeable Concept Field for third category");
		category3.add(new Coding(CATEGORYFIRSTCODINGSYSTEM, "test-vitals-panel", "test-vitals-panel display"));
		category3.add(new Coding("http://myalternatecodes.org/fhir/observation-category", "test-alt-vitals-panel", "test-alt-vitals-panel display"));
		category3.add(new Coding("http://mysecondaltcodes.org/fhir/observation-category", "test-2nd-alt-vitals-panel", "test-2nd-alt-vitals-panel display"));
		categoryCodeableConcept3.setCoding(category3);
		categoryConcepts.add(categoryCodeableConcept3);
		return categoryConcepts;
	}

	private CodeableConcept getObservationCode() {
		// Create CodeableConcept for Code with three codings.
		CodeableConcept codeableConceptField = new CodeableConcept().setText(SINGLE_OBSERVATION_CODE_TEXT);
		codeableConceptField.addCoding(new Coding(CODEFIRSTCODINGSYSTEM, CODEFIRSTCODINGCODE, "test-code display"));
		// TODO: Temporarily limit this to a single Observation Code coding until we sort out how to manage multiple codings
//		codeableConceptField.addCoding(new Coding("http://myalternatecodes.org/fhir/observation-code", "test-alt-code", "test-alt-code display"));
//		codeableConceptField.addCoding(new Coding("http://mysecondaltcodes.org/fhir/observation-code", "test-second-alt-code", "test-second-alt-code display"));
		return codeableConceptField;
	}

	@Test
	public void testIndexObservationMultiple() {
		indexMultipleObservations();
		assertEquals(100, myResourceIndexedObservationLastNDao.count());
		assertEquals(2, myCodeableConceptIndexedSearchParamNormalizedDao.count());

		// Check that all observations were indexed.
		SearchParameterMap searchParameterMap = new SearchParameterMap();
		searchParameterMap.add(Observation.SP_SUBJECT, multiSubjectParams);
		//searchParameterMap.
		List<String> observationIdsOnly = elasticsearchSvc.executeLastN(searchParameterMap, 10, 200);
		assertEquals(100, observationIdsOnly.size());

		// Filter the results by category code.
		TokenParam categoryParam = new TokenParam(CATEGORYFIRSTCODINGSYSTEM, FIRSTCATEGORYFIRSTCODINGCODE);
		searchParameterMap.add(Observation.SP_CATEGORY, new TokenAndListParam().addAnd(new TokenOrListParam().addOr(categoryParam)));

		observationIdsOnly = elasticsearchSvc.executeLastN(searchParameterMap, 10, 100);

		assertEquals(50, observationIdsOnly.size());

	}

	private void indexMultipleObservations() {

		// Create two CodeableConcept values each for a Code with three codings.
		CodeableConcept codeableConceptField1 = new CodeableConcept().setText("Test Codeable Concept Field for First Code");
		codeableConceptField1.addCoding(new Coding(CODEFIRSTCODINGSYSTEM, "test-code-1", "test-code-1 display"));
		codeableConceptField1.addCoding(new Coding("http://myalternatecodes.org/fhir/observation-code", "test-alt-code-1", "test-alt-code-1 display"));
		codeableConceptField1.addCoding(new Coding("http://mysecondaltcodes.org/fhir/observation-code", "test-second-alt-code-1", "test-second-alt-code-1 display"));

		CodeableConcept codeableConceptField2 = new CodeableConcept().setText("Test Codeable Concept Field for Second Code");
		codeableConceptField2.addCoding(new Coding(CODEFIRSTCODINGSYSTEM, "test-code-2", "test-code-2 display"));
		codeableConceptField2.addCoding(new Coding("http://myalternatecodes.org/fhir/observation-code", "test-alt-code-2", "test-alt-code-2 display"));
		codeableConceptField2.addCoding(new Coding("http://mysecondaltcodes.org/fhir/observation-code", "test-second-alt-code-2", "test-second-alt-code-2 display"));

		// Create two CodeableConcept entities for category, each with three codings.
		List<Coding> category1 = new ArrayList<>();
		// Create three codings and first category CodeableConcept
		category1.add(new Coding(CATEGORYFIRSTCODINGSYSTEM, FIRSTCATEGORYFIRSTCODINGCODE, "test-heart-rate display"));
		category1.add(new Coding("http://myalternatecodes.org/fhir/observation-category", "test-alt-heart-rate", "test-alt-heart-rate display"));
		category1.add(new Coding("http://mysecondaltcodes.org/fhir/observation-category", "test-2nd-alt-heart-rate", "test-2nd-alt-heart-rate display"));
		List<CodeableConcept> categoryConcepts1 = new ArrayList<>();
		CodeableConcept categoryCodeableConcept1 = new CodeableConcept().setText("Test Codeable Concept Field for first category");
		categoryCodeableConcept1.setCoding(category1);
		categoryConcepts1.add(categoryCodeableConcept1);
		// Create three codings and second category CodeableConcept
		List<Coding> category2 = new ArrayList<>();
		category2.add(new Coding(CATEGORYFIRSTCODINGSYSTEM, "test-vital-signs", "test-vital-signs display"));
		category2.add(new Coding("http://myalternatecodes.org/fhir/observation-category", "test-alt-vitals", "test-alt-vitals display"));
		category2.add(new Coding("http://mysecondaltcodes.org/fhir/observation-category", "test-2nd-alt-vitals", "test-2nd-alt-vitals display"));
		List<CodeableConcept> categoryConcepts2 = new ArrayList<>();
		CodeableConcept categoryCodeableConcept2 = new CodeableConcept().setText("Test Codeable Concept Field for second category");
		categoryCodeableConcept2.setCoding(category2);
		categoryConcepts2.add(categoryCodeableConcept2);

		ReferenceOrListParam subjectParams = new ReferenceOrListParam();
		for (int patientCount = 0; patientCount < 10; patientCount++) {

			String subjectId = String.valueOf(patientCount);

			ReferenceParam subjectParam = new ReferenceParam("Patient", "", subjectId);
			subjectParams.addOr(subjectParam);

			for (int entryCount = 0; entryCount < 10; entryCount++) {

				Observation observation = new Observation();
				IdType observationId = new IdType("Observation", String.valueOf(entryCount + patientCount * 10), "1");
				observation.setId(observationId);
				Reference subject = new Reference(subjectId);
				observation.setSubject(subject);

				if (entryCount % 2 == 1) {
					observation.setCategory(categoryConcepts1);
					observation.setCode(codeableConceptField1);
				} else {
					observation.setCategory(categoryConcepts2);
					observation.setCode(codeableConceptField2);
				}

				Calendar observationDate = new GregorianCalendar();
				observationDate.add(Calendar.HOUR, -10 + entryCount);
				Date effectiveDtm = observationDate.getTime();
				observation.setEffective(new DateTimeType(effectiveDtm));

//				myObservationLastNIndexPersistR4Svc.indexObservation(observation);

				testObservationPersist.indexObservation(observation);
			}

		}

		multiSubjectParams = new ReferenceAndListParam().addAnd(subjectParams);

	}

	@Test
	public void testDeleteObservation() {
		indexMultipleObservations();
		assertEquals(100, myResourceIndexedObservationLastNDao.count());
		// Check that fifth observation for fifth patient has been indexed.
		ObservationIndexedSearchParamLastNEntity observation = myResourceIndexedObservationLastNDao.findForIdentifier("55");
		assertNotNull(observation);

		SearchParameterMap searchParameterMap = new SearchParameterMap();
		searchParameterMap.add(Observation.SP_SUBJECT, multiSubjectParams);
		List<String> observationIdsOnly = elasticsearchSvc.executeLastN(searchParameterMap, 10, 200);
		assertEquals(100, observationIdsOnly.size());
		assertTrue(observationIdsOnly.contains("55"));

		// Delete fifth observation for fifth patient.
		ResourceTable entity = new ResourceTable();
		entity.setId(55L);
		entity.setResourceType("Observation");
		entity.setVersion(0L);

//		myObservationLastNIndexPersistR4Svc.deleteObservationIndex(entity);
		testObservationPersist.deleteObservationIndex(entity);

		// Confirm that observation was deleted.
		assertEquals(99, myResourceIndexedObservationLastNDao.count());
		observation = myResourceIndexedObservationLastNDao.findForIdentifier("55");
		assertNull(observation);

		observationIdsOnly = elasticsearchSvc.executeLastN(searchParameterMap, 10, 200);
		assertEquals(99, observationIdsOnly.size());
		assertTrue(!observationIdsOnly.contains("55"));

	}

	@Test
	public void testUpdateObservation() {
		indexSingleObservation();
		ObservationIndexedSearchParamLastNEntity observationIndexEntity = myResourceIndexedObservationLastNDao.findAll().get(0);
		assertEquals(SINGLE_OBSERVATION_PID, observationIndexEntity.getIdentifier());
		assertEquals(SINGLE_SUBJECT_ID, observationIndexEntity.getSubject());
		assertEquals(SINGLE_EFFECTIVEDTM, observationIndexEntity.getEffectiveDtm());

		SearchParameterMap searchParameterMap = new SearchParameterMap();
		ReferenceParam subjectParam = new ReferenceParam("Patient", "", SINGLE_SUBJECT_ID);
		searchParameterMap.add(Observation.SP_SUBJECT, new ReferenceAndListParam().addAnd(new ReferenceOrListParam().addOr(subjectParam)));
		TokenParam categoryParam = new TokenParam(CATEGORYFIRSTCODINGSYSTEM, FIRSTCATEGORYFIRSTCODINGCODE);
		searchParameterMap.add(Observation.SP_CATEGORY, new TokenAndListParam().addAnd(new TokenOrListParam().addOr(categoryParam)));
		TokenParam codeParam = new TokenParam(CODEFIRSTCODINGSYSTEM, CODEFIRSTCODINGCODE);
		searchParameterMap.add(Observation.SP_CODE, new TokenAndListParam().addAnd(new TokenOrListParam().addOr(codeParam)));
		List<String> observationIdsOnly = elasticsearchSvc.executeLastN(searchParameterMap, 10, 200);
		assertEquals(1, observationIdsOnly.size());
		assertTrue(observationIdsOnly.contains(SINGLE_OBSERVATION_PID));

		// Update the Observation with a new Subject and effective date:
		Observation updatedObservation = new Observation();
		IdType observationId = new IdType("Observation", observationIndexEntity.getIdentifier(), "2");
		updatedObservation.setId(observationId);
		Reference subjectId = new Reference("1234");
		updatedObservation.setSubject(subjectId);
		DateTimeType newEffectiveDtm = new DateTimeType(new Date());
		updatedObservation.setEffective(newEffectiveDtm);
		updatedObservation.setCategory(getCategoryCode());
		updatedObservation.setCode(getObservationCode());

//		myObservationLastNIndexPersistR4Svc.indexObservation(updatedObservation);
		testObservationPersist.indexObservation(updatedObservation);

		ObservationIndexedSearchParamLastNEntity updatedObservationEntity = myResourceIndexedObservationLastNDao.findForIdentifier(SINGLE_OBSERVATION_PID);
		assertEquals("1234", updatedObservationEntity.getSubject());
		assertEquals(newEffectiveDtm.getValue(), updatedObservationEntity.getEffectiveDtm());

		// Repeat earlier Elasticsearch query. This time, should return no matches.
		observationIdsOnly = elasticsearchSvc.executeLastN(searchParameterMap, 10, 200);
		assertEquals(0, observationIdsOnly.size());

		// Try again with the new patient ID.
		searchParameterMap = new SearchParameterMap();
		subjectParam = new ReferenceParam("Patient", "", "1234");
		searchParameterMap.add(Observation.SP_SUBJECT, new ReferenceAndListParam().addAnd(new ReferenceOrListParam().addOr(subjectParam)));
		searchParameterMap.add(Observation.SP_CATEGORY, new TokenAndListParam().addAnd(new TokenOrListParam().addOr(categoryParam)));
		searchParameterMap.add(Observation.SP_CODE, new TokenAndListParam().addAnd(new TokenOrListParam().addOr(codeParam)));
		observationIdsOnly = elasticsearchSvc.executeLastN(searchParameterMap, 10, 200);

		// Should see the observation returned now.
		assertEquals(1, observationIdsOnly.size());
		assertTrue(observationIdsOnly.contains(SINGLE_OBSERVATION_PID));

	}

	@Test
	public void testSampleBundleInTransaction() throws IOException {
		FhirContext myFhirCtx = FhirContext.forR4();

		PathMatchingResourcePatternResolver provider = new PathMatchingResourcePatternResolver();
		final Resource[] bundleResources;
		bundleResources = provider.getResources("lastntestbundle.json");

		AtomicInteger index = new AtomicInteger();

		Arrays.stream(bundleResources).forEach(
			resource -> {
				index.incrementAndGet();

				InputStream resIs = null;
				String nextBundleString;
				try {
					resIs = resource.getInputStream();
					nextBundleString = IOUtils.toString(resIs, Charsets.UTF_8);
				} catch (IOException e) {
					return;
				} finally {
					try {
						if (resIs != null) {
							resIs.close();
						}
					} catch (final IOException ioe) {
						// ignore
					}
				}

				IParser parser = myFhirCtx.newJsonParser();
				Bundle bundle = parser.parseResource(Bundle.class, nextBundleString);

				myDao.transaction(null, bundle);

			}
		);

		SearchParameterMap searchParameterMap = new SearchParameterMap();

		// execute Observation ID search - Composite Aggregation
		List<String>  observationIdsOnly = elasticsearchSvc.executeLastN(searchParameterMap,1, 200);

		assertEquals(20, observationIdsOnly.size());

		observationIdsOnly = elasticsearchSvc.executeLastN(searchParameterMap, 3, 200);

		assertEquals(38, observationIdsOnly.size());

	}


}