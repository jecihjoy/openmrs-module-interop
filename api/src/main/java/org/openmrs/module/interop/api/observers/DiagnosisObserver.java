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

import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.openmrs.ConditionVerificationStatus;
import org.openmrs.Diagnosis;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.event.Event;
import org.openmrs.module.interop.api.Subscribable;
import org.openmrs.module.interop.api.metadata.EventMetadata;
import org.openmrs.module.interop.api.processors.translators.InteropConditionTranslator;
import org.openmrs.module.interop.utils.ObserverUtils;
import org.openmrs.module.interop.utils.ReferencesUtil;

import javax.jms.Message;
import javax.validation.constraints.NotNull;
import java.util.List;

@Slf4j
public class DiagnosisObserver extends BaseObserver implements Subscribable<Diagnosis> {
	
	private InteropConditionTranslator<Diagnosis> diagnosisTranslator;
	
	@Override
	public Class<?> clazz() {
		return Diagnosis.class;
	}
	
	@Override
	public List<Event.Action> actions() {
		return ObserverUtils.defaultActions();
	}
	
	@Override
	public void onMessage(Message message) {
		processMessage(message)
		        .ifPresent(metadata -> Daemon.runInDaemonThread(() -> prepareDiagnosisMessage(metadata), getDaemonToken()));
	}
	
	private void prepareDiagnosisMessage(@NotNull EventMetadata metadata) {
		Diagnosis diagnosis = Context.getDiagnosisService().getDiagnosisByUuid(metadata.getString("uuid"));
		if (diagnosis.getCertainty() == null || diagnosis.getCertainty().equals(ConditionVerificationStatus.PROVISIONAL))
			return;
		org.hl7.fhir.r4.model.Condition fhirCondition = diagnosisTranslator.toFhirResource(diagnosis);
		if (fhirCondition != null) {
			if (diagnosis.getRank().equals(new Integer(1))) {
				fhirCondition.setVerificationStatus(new CodeableConcept().addCoding(
				    new Coding("http://terminology.hl7.org/CodeSystem/condition-ver-status", "provisional", "Provisional")));
			} else if (diagnosis.getRank().equals(new Integer(2))) {
				fhirCondition.setVerificationStatus(new CodeableConcept().addCoding(
				    new Coding("http://terminology.hl7.org/CodeSystem/condition-ver-status", "confirmed", "Confirmed")));
			}
			String reference = fhirCondition.getSubject().getReference();
			String arr[] = reference.split("/");
			if (arr.length == 2) {
				Patient patient = Context.getPatientService().getPatientByUuid(arr[1]);
				fhirCondition.setSubject(ReferencesUtil.buildPatientReference(patient));
			}
			this.publish(fhirCondition);
		} else {
			log.error("Couldn't find diagnosis with UUID {} ", metadata.getString("uuid"));
		}
	}
}
