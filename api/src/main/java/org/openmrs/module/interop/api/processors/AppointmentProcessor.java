/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.interop.api.processors;

import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;
import org.openmrs.module.interop.InteropConstant;
import org.openmrs.module.interop.api.InteropProcessor;
import org.openmrs.module.interop.api.processors.translators.AppointmentObsTranslator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component("interop.appointmentProcessor")
public class AppointmentProcessor implements InteropProcessor<Encounter> {
	
	@Autowired
	@Qualifier("interop.appointments")
	private AppointmentObsTranslator appointmentObsTranslator;
	
	@Autowired
	private ConceptTranslator conceptTranslator;
	
	@Override
	public List<String> encounterTypes() {
		
		return Arrays.asList(Context.getAdministrationService()
		        .getGlobalPropertyValue(InteropConstant.APPOINTMENT_PROCESSOR_ENCOUNTER_TYPE_UUIDS, "").split(","));
	}
	
	@Override
	public List<String> questions() {
		String appointmentString = Context.getAdministrationService()
		        .getGlobalPropertyValue(InteropConstant.APPOINTMENT_CONCEPT_UUID, "");
		
		return Arrays.asList(appointmentString.split(","));
	}
	
	public Map<String, String> getAppointmentMapping() {
		Map<String, String> appointmentMapping = new HashMap<>();
		questions().forEach(q -> {
			String[] keyVal = q.split(":");
			appointmentMapping.put(keyVal[0], keyVal[1]);
			
		});
		return appointmentMapping;
	}
	
	@Override
	public List<String> forms() {
		return null;
	}
	
	@Override
	public List<Appointment> process(Encounter encounter) {
		System.out.println("Started appointment");
		List<Obs> allObs = new ArrayList<>(encounter.getAllObs());
		Map<String, String> appointmentMapping = getAppointmentMapping();
		
		List<Obs> appointmentObs = new ArrayList<>();
		List<Obs> appointmentTypeObs = new ArrayList<>();
		
		if (validateEncounterType(encounter)) {
			allObs.forEach(obs -> {
				if (validateConceptQuestions(obs)) {
					appointmentObs.add(obs);
				}
				if (validateAppointmentTypeQuestions(obs)) {
					appointmentTypeObs.add(obs);
				}
				
			});
		}
		
		List<Appointment> appointments = new ArrayList<>();
		if (!appointmentObs.isEmpty()) {
			appointmentObs.forEach(obs -> {
				Appointment appointment = appointmentObsTranslator.toFhirResource(obs);
				String appointmentTypeString = appointmentMapping.get(obs.getConcept().getUuid());
				
				appointmentTypeObs.forEach(type -> {
					if (type.getConcept().getUuid() == appointmentTypeString) {
						appointment.addServiceType(conceptTranslator.toFhirResource(type.getConcept()));
					}
					if (appointmentTypeString.isEmpty()) {
						appointment.addServiceType(
						    new CodeableConcept().addCoding(new Coding("", "Medication refill", "Refill")));
					}
				});
				
				System.out.println("encounter ***********************= 110" + appointment.getStart());
				
				appointments.add(appointment);
			});
			
		}
		System.out.println("Started appointment" + appointments.size());
		return appointments;
	}
	
	private boolean validateEncounterType(Encounter encounter) {
		return encounterTypes().contains(encounter.getEncounterType().getUuid());
	}
	
	private boolean validateConceptQuestions(Obs conceptObs) {
		return getAppointmentMapping().keySet().contains(conceptObs.getConcept().getUuid());
	}
	
	private boolean validateAppointmentTypeQuestions(Obs conceptObs) {
		return getAppointmentMapping().values().contains(conceptObs.getConcept().getUuid());
	}
	
}
