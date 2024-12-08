/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.interop.api.observers;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.*;
import org.openmrs.Diagnosis;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.VisitType;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.event.Event;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;
import org.openmrs.module.fhir2.api.translators.EncounterReferenceTranslator;
import org.openmrs.module.fhir2.api.translators.EncounterTranslator;
import org.openmrs.module.fhir2.api.translators.ObservationTranslator;
import org.openmrs.module.interop.InteropConstant;
import org.openmrs.module.interop.api.Subscribable;
import org.openmrs.module.interop.api.metadata.EventMetadata;
import org.openmrs.module.interop.api.processors.AllergyIntoleranceProcessor;
import org.openmrs.module.interop.api.processors.AppointmentProcessor;
import org.openmrs.module.interop.api.processors.ConditionProcessor;
import org.openmrs.module.interop.api.processors.DiagnosticReportProcessor;
import org.openmrs.module.interop.api.processors.ExamFindingsProcessor;
import org.openmrs.module.interop.api.processors.LabResultsProcessor;
import org.openmrs.module.interop.api.processors.ServiceRequestProcessor;
import org.openmrs.module.interop.api.processors.VitalsProcessor;
import org.openmrs.module.interop.api.processors.translators.AppointmentRequestTranslator;
import org.openmrs.module.interop.api.processors.translators.InteropConditionTranslator;
import org.openmrs.module.interop.utils.ObserverUtils;
import org.openmrs.module.interop.utils.ReferencesUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import javax.jms.Message;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.openmrs.module.interop.utils.ReferencesUtil.buildProviderIdentifier;

@Slf4j
@Component("interop.encounterCreationObserver")
public class EncounterObserver extends BaseObserver implements Subscribable<org.openmrs.Encounter> {
	
	@Autowired
	private EncounterTranslator<Encounter> encounterTranslator;
	
	@Autowired
	private EncounterReferenceTranslator<Encounter> encounterReferenceTranslator;
	
	@Autowired
	private ObservationTranslator observationTranslator;
	
	@Autowired
	private ConditionProcessor conditionProcessor;
	
	@Autowired
	ExamFindingsProcessor examFindingsProcessor;
	
	@Qualifier("interop.appointmentProcessor")
	private AppointmentProcessor appointmentProcessor;
	
	@Qualifier("interop.appointmentRequestTranslator")
	private AppointmentRequestTranslator appointmentRequestTranslator;
	
	@Autowired
	private InteropConditionTranslator<Diagnosis> diagnosisTranslator;
	
	@Autowired
	private DiagnosticReportProcessor diagnosticReportProcessor;
	
	private AllergyIntoleranceProcessor allergyIntoleranceProcessor;
	
	@Autowired
	private VitalsProcessor vitalsProcessor;
	
	private ServiceRequestProcessor serviceRequestProcessor;
	
	@Autowired
	private ConceptTranslator conceptTranslator;
	
	private LabResultsProcessor labResultsProcessor;
	
	@Override
	public Class<?> clazz() {
		return Encounter.class;
	}
	
	@Override
	public List<Event.Action> actions() {
		return ObserverUtils.defaultActions();
	}
	
	@Override
	public void onMessage(Message message) {
		processMessage(message).ifPresent(metadata -> {
		    //formatter:off
		    Daemon.runInDaemonThread(() -> prepareEncounterMessage(metadata), getDaemonToken());
			//formatter:on
		});
	}
	
	private void prepareEncounterMessage(@NotNull EventMetadata metadata) {
		//Create bundle
		Encounter encounter = Context.getEncounterService().getEncounterByUuid(metadata.getString("uuid"));
		Bundle preparedBundle = new Bundle();
		preparedBundle.setType(Bundle.BundleType.TRANSACTION);
		
		org.hl7.fhir.r4.model.Encounter fhirEncounter = encounterTranslator.toFhirResource(encounter);
		fhirEncounter.setSubject(ReferencesUtil.buildPatientReference(encounter.getPatient()));
		org.hl7.fhir.r4.model.Encounter.EncounterLocationComponent locationComponent = new org.hl7.fhir.r4.model.Encounter.EncounterLocationComponent();
		locationComponent.setLocation(ReferencesUtil.buildKhmflLocationReference(encounter.getLocation()));
		fhirEncounter.setLocation(Collections.singletonList(locationComponent));
		fhirEncounter.getParticipant().clear();
		fhirEncounter.setPartOf(new Reference());
		fhirEncounter.setIdentifier(Collections.singletonList(
		    new Identifier().setSystem("https://shr.kenya-hie.health/encounter").setValue(encounter.getUuid())));
		fhirEncounter.setStatus(org.hl7.fhir.r4.model.Encounter.EncounterStatus.FINISHED);
		
		if (encounter.getVisit() != null) {
			VisitType encounterVisit = encounter.getVisit().getVisitType();
			if (encounterVisit.getName().equals("Outpatient")) {
				fhirEncounter.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "OP", "Outpatient"));
			} else if (encounterVisit.getName().equals("Inpatient")) {
				fhirEncounter.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "IP", "Inpatient"));
			}
		}
		fhirEncounter.setPriority(new CodeableConcept().addCoding(new Coding("http://snomed.info/sct", "normal", "Normal")));
		
		fhirEncounter.getContained().clear();
		List<Obs> visitOutcome = encounter.getObs().stream()
		        .filter(e -> e.getConcept().getUuid().equals("160433AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
		        .collect(Collectors.toList());
		if (!visitOutcome.isEmpty()) {
			fhirEncounter.addExtension(new Extension().setUrl("patient-outcome")
			        .setValue(new StringType().setValue(visitOutcome.get(0).getValueCoded().getName().getName())));
		}
		
		Bundle.BundleEntryComponent encounterBundleEntryComponent = new Bundle.BundleEntryComponent();
		Bundle.BundleEntryRequestComponent bundleEntryRequestComponent = new Bundle.BundleEntryRequestComponent();
		bundleEntryRequestComponent.setMethod(Bundle.HTTPVerb.PUT);
		bundleEntryRequestComponent.setUrl("Encounter/" + fhirEncounter.getId());
		encounterBundleEntryComponent.setRequest(bundleEntryRequestComponent);
		encounterBundleEntryComponent.setResource(fhirEncounter);
		preparedBundle.addEntry(encounterBundleEntryComponent);
		preparedBundle.setId(encounter.getUuid());
		preparedBundle.setIdentifier(
		    new Identifier().setSystem("https://shr.kenya-hie.health/encounter").setValue(encounter.getUuid()));
		
		/*Vital obs*/
		List<Observation> vitalsObs = vitalsProcessor.process(encounter);
		for (Observation obs : vitalsObs) {
			Bundle.BundleEntryComponent obsBundleEntry = new Bundle.BundleEntryComponent();
			Bundle.BundleEntryRequestComponent requestComponent = new Bundle.BundleEntryRequestComponent();
			requestComponent.setMethod(Bundle.HTTPVerb.PUT);
			requestComponent.setUrl("Observation/" + obs.getId());
			obsBundleEntry.setRequest(requestComponent);
			obsBundleEntry.setResource(obs);
			preparedBundle.addEntry(obsBundleEntry);
		}
		
		/*Lab results obs*/
		List<Observation> labResultsObs = new ArrayList<>(); // labResultsProcessor.process(encounter);
		for (Observation obs : labResultsObs) {
			preparedBundle.addEntry(buildGenericComponent(obs, "Observation"));
		}
		FhirContext fhirContext = FhirContext.forR4();
		IParser parser = fhirContext.newJsonParser();
		System.out.printf("------------------------------------" + parser.encodeResourceToString(preparedBundle));
		this.processFhirResources(encounter, preparedBundle);
		this.publish(preparedBundle);
	}
	
	private void processFhirResources(@Nonnull Encounter encounter, @NotNull Bundle bundle) {
		
		/*Chief complaint*/
		List<Condition> conditions = conditionProcessor.process(encounter);
		conditions.forEach(condition -> {
			condition.setSubject(ReferencesUtil.buildPatientReference(encounter.getPatient()));
			condition.getRecorder().setIdentifier(buildProviderIdentifier(encounter));
			condition.setEncounter(encounterReferenceTranslator.toFhirResource(encounter));
			bundle.addEntry(buildGenericComponent(condition, "Condition"));
		});
		
		/*Encounter diagnosis*/
		if (!encounter.getDiagnoses().isEmpty()) {
			encounter.getDiagnoses().forEach(d -> {
				Condition disease = diagnosisTranslator.toFhirResource(d);
				disease.setSubject(ReferencesUtil.buildPatientReference(encounter.getPatient()));
				disease.getRecorder().setIdentifier(buildProviderIdentifier(encounter));
				disease.setEncounter(encounterReferenceTranslator.toFhirResource(encounter));
				bundle.addEntry(buildGenericComponent(disease, "Condition"));
			});
		}
		
		/*Examination findings*/
		List<Observation> examFindings = examFindingsProcessor.process(encounter);
		examFindings.forEach(e -> {
			e.setSubject(ReferencesUtil.buildPatientReference(encounter.getPatient()));
			//            d.getRecorder().setIdentifier(buildProviderIdentifier(encounter));
			e.setEncounter(encounterReferenceTranslator.toFhirResource(encounter));
			bundle.addEntry(buildGenericComponent(e, "Observation"));
		});
		
		//		List<Appointment> appointments = appointmentProcessor.process(encounter);
		//		if (!appointments.isEmpty()) {
		//			ServiceRequest serviceRequest = appointmentRequestTranslator.toFhirResource(encounter);
		//			serviceRequest.setSubject(ReferencesUtil.buildPatientReference(encounter.getPatient()));
		//			Reference locationRef = ReferencesUtil.buildKhmflOrganizationReference(encounter.getLocation());
		//			serviceRequest.setRequester(locationRef);
		//			bundle.addEntry(buildGenericComponent(serviceRequest, "ServiceRequest"));
		//
		//			appointments.forEach(appointment -> {
		//				List<Resource> resources = ReferencesUtil.resolveProvenceReference(appointment.getContained(), encounter);
		//				appointment
		//				        .setBasedOn(Collections.singletonList(ReferencesUtil.buildServiceRequestReference(serviceRequest)));
		//				appointment.getContained().clear();
		//				appointment.setContained(resources);
		//
		//				for (Appointment.AppointmentParticipantComponent participantComponent : appointment.getParticipant()) {
		//					participantComponent.setActor(ReferencesUtil.buildPatientReference(encounter.getPatient()));
		//				}
		//				bundle.addEntry(buildGenericComponent(appointment, "ServiceRequest"));
		//			});
		//		}
		
		List<AllergyIntolerance> allergyIntolerancesList = allergyIntoleranceProcessor.process(encounter);
		allergyIntolerancesList.forEach(allergy -> {
			allergy.setPatient(ReferencesUtil.buildPatientReference(encounter.getPatient()));
			allergy.setEncounter(encounterReferenceTranslator.toFhirResource(encounter));
			bundle.addEntry(buildGenericComponent(allergy, "AllergyIntolerance"));
		});
		
		/*List<DiagnosticReport> diagnosticReports = diagnosticReportProcessor.process(encounter);
		if (!diagnosticReports.isEmpty()) {
			diagnosticReports.get(0).setEncounter(encounterReferenceTranslator.toFhirResource(encounter));
			diagnosticReports.get(0).setSubject(ReferencesUtil.buildPatientReference(encounter.getPatient()));
			bundle.addEntry(createDiagnosticReportComponent(diagnosticReports.get(0)));
		}
		
		List<ServiceRequest> serviceRequests = serviceRequestProcessor.process(encounter);
		if (!serviceRequests.isEmpty()) {
			serviceRequests.get(0).setSubject(ReferencesUtil.buildPatientReference(encounter.getPatient()));
			serviceRequests.get(0).setEncounter(encounterReferenceTranslator.toFhirResource(encounter));
			bundle.addEntry(createServiceRequestBundleComponent(serviceRequests.get(0)));
		}; */
		
		//		if (!buildCancerScreeningReferralInfo(encounter).isEmpty()) {
		//			for (Bundle.BundleEntryComponent component : buildCancerScreeningReferralInfo(encounter)) {
		//				bundle.addEntry(component);
		//			}
		//		}
		
	}
	
	private Bundle.BundleEntryComponent buildGenericComponent(DomainResource domainResource, String resourcePath) {
		Bundle.BundleEntryRequestComponent bundleEntryRequestComponent = new Bundle.BundleEntryRequestComponent();
		bundleEntryRequestComponent.setMethod(Bundle.HTTPVerb.PUT);
		bundleEntryRequestComponent.setUrl(resourcePath + "/" + domainResource.getId());
		Bundle.BundleEntryComponent bundleEntryComponent = new Bundle.BundleEntryComponent();
		bundleEntryComponent.setRequest(bundleEntryRequestComponent);
		bundleEntryComponent.setResource(domainResource);
		return bundleEntryComponent;
	}
	
	private List<Bundle.BundleEntryComponent> buildCancerScreeningReferralInfo(Encounter encounter) {
		List<Obs> cancerScreeningObs = new ArrayList<>();
		encounter.getObs().forEach(ob -> {
			if (validateConceptScreeningObs(ob)) {
				cancerScreeningObs.add(ob);
			}
		});
		
		if (!cancerScreeningObs.isEmpty()) {
			List<Bundle.BundleEntryComponent> bundleEntryComponentList = new ArrayList<>();
			List<String> findings = Arrays.asList(Context.getAdministrationService()
			        .getGlobalPropertyValue(InteropConstant.CANCER_SCREENING_FINDINGS_CONCEPT_UUID, "").split(","));
			List<String> txPlan = Arrays.asList(Context.getAdministrationService()
			        .getGlobalPropertyValue(InteropConstant.CANCER_SCREENING_ACTION_CONCEPT_UUID, "").split(","));
			List<Reference> obsRefs = new ArrayList<>();
			cancerScreeningObs.forEach(r -> {
				Bundle.BundleEntryComponent obsBundleEntry = new Bundle.BundleEntryComponent();
				Bundle.BundleEntryRequestComponent requestComponent = new Bundle.BundleEntryRequestComponent();
				requestComponent.setMethod(Bundle.HTTPVerb.PUT);
				Observation observation = observationTranslator.toFhirResource(r);
				observation.setSubject(ReferencesUtil.buildPatientReference(encounter.getPatient()));
				
				obsRefs.add(new Reference(r.getUuid()).setType("Observation"));
				
				if (r.getObsGroup() != null) {
					
					List<Obs> findingsObs = encounter.getObs().stream()
					        .filter(
					            f -> f.getObsGroup() != null && r.getObsGroup().getUuid().equals(f.getObsGroup().getUuid())
					                    && findings.contains(f.getConcept().getUuid()))
					        .collect(Collectors.toList());
					List<Obs> txPlanObs = encounter.getObs().stream()
					        .filter(
					            f -> f.getObsGroup() != null && r.getObsGroup().getUuid().equals(f.getObsGroup().getUuid())
					                    && txPlan.contains(f.getConcept().getUuid()))
					        .collect(Collectors.toList());
					
					observation.setCode(
					    new CodeableConcept().addCoding(new Coding("https://openconceptlab.org/orgs/CIEL/sources/CIEL",
					            r.getValueCoded().getUuid().replace("A", ""), r.getValueCoded().getDisplayString())));
					
					CodeableConcept findingsCode = new CodeableConcept();
					List<String> txPlanCode = new ArrayList<>();
					if (!findingsObs.isEmpty()) {
						findingsObs.forEach(e -> {
							findingsCode.addCoding(new Coding("https://openconceptlab.org/orgs/CIEL/sources/CIEL",
							        e.getValueCoded().getUuid().replace("A", ""), "")
							                .setDisplay(e.getValueCoded().getDisplayString()));
						});
					}
					if (!txPlanObs.isEmpty()) {
						txPlanObs.forEach(e -> {
							txPlanCode.add(e.getValueCoded().getDisplayString());
						});
					}
					observation.setValue(findingsCode);
					observation.addNote(new Annotation().setText(String.join(",", txPlanCode)));
				}
				requestComponent.setUrl("Observation/" + observation.getId());
				obsBundleEntry.setRequest(requestComponent);
				obsBundleEntry.setResource(observation);
				bundleEntryComponentList.add(obsBundleEntry);
			});
			return bundleEntryComponentList;
		}
		return new ArrayList<>();
	}
	
	private boolean validateConceptScreeningObs(Obs conceptObs) {
		List<String> concepts = Arrays.asList(Context.getAdministrationService()
		        .getGlobalPropertyValue(InteropConstant.CANCER_SCREENING_CONCEPT_UUID, "").split(","));
		return concepts.contains(conceptObs.getConcept().getUuid());
	}
}
