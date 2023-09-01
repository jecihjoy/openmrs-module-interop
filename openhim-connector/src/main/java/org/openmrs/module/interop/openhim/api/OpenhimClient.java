/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.interop.openhim.api;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;

public class OpenhimClient {
	
	public static void postFhirResource(String fhirResource, String openHimUrl) throws Exception {
		HttpClient httpClient = HttpClients.createDefault();
		HttpPost httpPost = new HttpPost(openHimUrl);
		String token = "Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6IkU0MUU1QUM5RUIxNTlBMjc1NTY4NjM0MzIxMUJDQzAzMDMyMEUzMTZSUzI1NiIsIng1dCI6IjVCNWF5ZXNWbWlkVmFHTkRJUnZNQXdNZzR4WSIsInR5cCI6ImF0K2p3dCJ9.eyJpc3MiOiJodHRwczovL2RocGlkZW50aXR5c3RhZ2luZ2FwaS5oZWFsdGguZ28ua2UiLCJuYmYiOjE2OTM1NTk1MjIsImlhdCI6MTY5MzU1OTUyMiwiZXhwIjoxNjkzNjQ1OTIyLCJhdWQiOlsiREhQLkdhdGV3YXkiLCJESFAuUGFydG5lcnMiXSwic2NvcGUiOlsiREhQLkdhdGV3YXkiLCJESFAuUGFydG5lcnMiXSwiY2xpZW50X2lkIjoicGFydG5lci50ZXN0LmNsaWVudCIsImp0aSI6IjQxMDhBOEY2RkZDRTlFN0Q1M0ZGQkI0OUQzMDU1Q0VEIn0.T9go41MWh0cgoaX3YQDQqag9dUvbYEzYfaKvvs63QEMQ8iU72GtvaeoOhqS7Kzq-84ooaB73Lya4oM3Ua0kxk_jQ4HkMnG7o5NpYeMYXealoj2hTbCkgGta1XLVIter9Ozy7YAFMAaPPP_dBGb4kZQI9vWjSfyJh5ib_Hjq_J48OszhZOr3s9EMIXsTyL8SkzcjjY2rjJY05uaT0d3ev9RKOHC_Kc-dA-YkCE7i5c5TqBnvjU64iW3wcAWkM8LDvRDc9NZ7Pvw2mG2dTMf5vQ-uVNCswSolkoaPBJcGnTE0AHx9I1Ss1d2TekrIZcOI530yZOiGmj9UVgawTaY3edQ";
		
		StringEntity fhirResourceEntity = new StringEntity(fhirResource);
		httpPost.setEntity(fhirResourceEntity);
		httpPost.setHeader("Content-type", "application/json");
		httpPost.setHeader("Authorization", token);
		System.out.println("TOKE ++++++++ " + token);
		
		HttpResponse response = httpClient.execute(httpPost);
		int statusCode = response.getStatusLine().getStatusCode();
		if (statusCode >= 200 && statusCode < 300) {
			System.out.println("FHIR resource was successfully posted to the OpenHIM channel");
		} else {
			String responseBody = response.getEntity().toString();
			System.out.println("An error occurred while posting the FHIR resource to the OpenHIM channel. Status code: "
			        + statusCode + " Response body: " + responseBody);
		}
	}
}
