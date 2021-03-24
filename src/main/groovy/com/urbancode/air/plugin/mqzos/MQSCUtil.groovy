/**
 * (C) Copyright IBM Corporation 2016, 2021.
 * (C) Copyright HCL Corporation 2018, 2021.
 * This is licensed under the following license.
 * The Eclipse Public 1.0 License (http://www.eclipse.org/legal/epl-v10.html)
 * U.S. Government Users Restricted Rights:  Use, duplication or disclosure restricted by GSA ADP Schedule Contract with IBM Corp.
 */

package com.urbancode.air.plugin.mqzos

import groovy.json.JsonSlurper

/**
 * MQSC util class to generate MQSC form JSON form resource definitions specified in a base,
 * overrides and properties file. An MQ resource attributes mappings file is used to facilitate
 * the conversion from the JSON file to the MQSC representation.
 */
class MQSCUtil {

	// Trace flag.
	def private trace
	def final static hashMapClass = "java.util.HashMap"
	def final static lazyMapClass = "groovy.json.internal.LazyMap"

	// Character set encoding for the platform where the UCD agent is running.
	def private charSetEncodingName

	/**
	 * Generates MQSC form resource definitions from JSON form resource definitions specified in a base,
	 * overrides and properties file.
	 *
	 * @param baseFile - file containing the base MQ resource definitions (in JSON form).
	 * @param overridesFile - file containing the override values for MQ resource definitions (in JSON form).
	 * @param propertiesFile - file containing property values (in JSON form) that are to be used to replace tokens in the MQ resource definitions.
	 * @param mqscResourceDefinitions - property variable to which the MQSC form of the resource definitions are written.
	 * @param targetEnvironmentName - target environment that resources are to be deployed to.
	 * @param traceEnabled = whether trace is enabled for this plugin.
	 * @return mqscResourceDefinitions - property variable to which the MQSC form of the resource definitions are written.
	 */
	def public generateMQSCFormDefinitions(baseFile, overridesFile, propertiesFile, mqscResourceDefinitions, targetEnvironmentName, traceEnabled) {

		if (traceEnabled) {
			println 'Entry: generateMQSCFormDefinitions'
			println ' Data: baseFile:'
			println baseFile
			println ' Data: overridesFile:'
			println overridesFile
			println ' Data: propertiesFile:'
			println propertiesFile
			println ' Data: mqscResourceDefinitions:'
			println mqscResourceDefinitions
			println ' Data: targetEnvironmentName: ' + targetEnvironmentName
		}

		// Set trace flag.
		trace = traceEnabled

		// Get the character set encoding value for this platform (i.e. the
		// platform where the UCD agent is running).
		charSetEncodingName = System.getProperty('file.encoding')

		// Get the UCD agent's work directory.
		final def agentWorkDir = new File('.').canonicalFile

		// Parse the base JSON format file and store the data into a base map.
		def baseMap = parseFile(baseFile)

		if (baseMap.size() == 0) {
			println '** WARNING: Basefile ' + baseFile + ' is empty. Associated overrides and properties files, if defined, will not be processed **'
		} else {
			// Parse the overrides file and build a map of name value pairs of the overriding attributes and values.
			def overridesMaps = buildOverridesMap(overridesFile, targetEnvironmentName)

			// Merge the base and overrides attributes into a map of name value pairs.
			def mergedMap = mergeBaseAndOverrides(baseMap, overridesMaps)

			// Check if any tokens in the mergedMap need to be replaced with their respective
			// values defined in the properties file. If not, we can just skip the substitution
			// and use the mergedMap as it stands.
			if (mergedMap.toString().contains('@')) {
				if (traceEnabled) {
					println '** INFORMATION: Found tokens wrapped between \'@\' symbols **'
				}

				// Substitute any property values in the merged map.
				mergedMap = substituteProperties(propertiesFile, mergedMap)
			}
			else {
				if (traceEnabled) {
					println '** INFORMATION: Did not find tokens wrapped between \'@\' symbols **'
				}
			}

			// Process any override name values.
			mergedMap = processOverrideNames(mergedMap)

			// Merge base and overrides attribute name values (with properties substituted).
			def mergedData = mergeDefinition(baseFile.name, agentWorkDir, mergedMap)

			// Parse resource attributes mappings file and get them back, sorted, in a map.
			def sortedResourceAttrsMap = parseResourceAttrs(getResourceAttributesFilePath())

			// Map REST attributes to MQSC attributes and write the MQSC format commands to the mqsc
			// resource definitions property variable.
			mqscResourceDefinitions = generateMQSCDefinitions(mergedData, sortedResourceAttrsMap, mqscResourceDefinitions)

			if (traceEnabled) {
				println ' Data: mqscResourceDefinitions' + mqscResourceDefinitions
				println 'Exit : generateMQSCFormDefinitions'
			}
		}
		
		// Pass back the updated mqsc resource definitions property variable.
		return mqscResourceDefinitions
	}

	/**
	 * Parse the overrides file and build a map of name value pairs of the overriding attributes and values.
	 *
	 * Note: If no override values need to be specified, an empty set { } is expected in the overrides file.
	 *       Otherwise, a request to parse with JsonSlurper results in a NullPointerException.
	 *
	 * @param overridesFile - file containing the override values for MQ resource definitions (in JSON form).
	 * @param targetEnvironmentName - target environment that resources are to be deployed to.
	 * @return overridesMaps - list containing the override maps (a map of the resources to be deployed and a map of the attributes and values as name value pairs).
	 */
	def private buildOverridesMap(overridesFile, targetEnvironmentName) {

		if (trace) {
			println 'Entry: buildOverridesMap'
		}

		// Read the overrides file.
		def overrides = parseFile(overridesFile)

		if (trace) {
			println ' Data: overrides: ' + overrides
		}

		// Note: For efficiency, variables are declared outside of the each loops.
		// Map for override resources to be deployed.
		def overrideResourcesMap = new HashMap()
		// Key for override resource to be deployed.
		def overrideResourceKey = ''
		// Map for overrides.
		def overridesMap = new HashMap()
		// Flag to indicate whether specific overrides were found or not.
		def specificOverridesFound
		// Index of wildcard in generic target environment name.
		def wildcardIndex
		// Generic target environment name.
		def genericTargetEnvironmentName = ''

		// Loop for each resource in the overrides file and build a map of override attributes.
		overrides.resource.each { typeOfResource->
			// Loop for each type (i.e. queue or channel) of resource(s).
			typeOfResource.value.each { resourceAttrs->
				// Build overrideResourceKey.
				overrideResourceKey = 'command:' + resourceAttrs.command + '::name:' + resourceAttrs.name + '::typeOfResource:' + typeOfResource.key + '::type:' + resourceAttrs.type

				// Loop for each attribute of each resource (e.g. command, name, type (which is actually the resource subtype), deploymentTargets). 
				resourceAttrs.each { resourceAttr->
					// If the attribute key is deploymentTargets (each resource of a given type essentially has one or more deployment targets),
					// we'll loop through each deploynment target. 
					if (resourceAttr.key == 'deploymentTargets') {
						specificOverridesFound = false
						// Loop for each deployment target.
						resourceAttr.value.each { dtAttrs->
							// If the deployment target equals the target environment name and override attributes are to be deployed,
							// specific overrides have been found so we process the specific override attributes.
							if (dtAttrs.targetEnvName == targetEnvironmentName && dtAttrs.deploy == true) {
								specificOverridesFound = true
								if (trace) {
									println ' Data: Specific Overrides Found, targetEnvironmentName = ' + targetEnvironmentName
									println ' Data: overrideResourceKey: ' + overrideResourceKey
								}

								// Loop for each group of attributes.	
								dtAttrs.each { dtAttrsGrp->
									if (trace) {
										println ' Data: dtAttrsGrp: ' + dtAttrsGrp + ' dtAttrsGrp.value.getClass(): ' + dtAttrsGrp.value.getClass()
									}

									// Add the key to the overrideResourcesMap if it has not already been added. 
									if (!overrideResourcesMap.containsKey(overrideResourceKey)) {
										overrideResourcesMap.put(overrideResourceKey, true)
									}

									// All override attributes must be specified within their containing attribute groups so we
									// expect them to be in a HashMap or LazyMap depending on the version of Groovy in use with UCD
									String dtAttrClass = dtAttrsGrp.value.getClass().toString()
									if (dtAttrClass.contains(hashMapClass) || dtAttrClass.contains(lazyMapClass))  {
										// Loop for each attribute in the attribute group.
										dtAttrsGrp.value.each { dtAttr->
											// Add the attribute override value to the overrides map.
											overridesMap.put('command:' + resourceAttrs.command + '::name:' + resourceAttrs.name + '::typeOfResource:' + typeOfResource.key + '::type:' + resourceAttrs.type + '::attrGrp:' + dtAttrsGrp.key + '::attrName:' + dtAttr.key, 'attrValue:' + dtAttr.value)
										}
									}
								}
							} else {
								// Specific overrides have not yet been found so we'll check if a generic target name has been defined.
								// Generic target names are defined with a single wild card of * (note, this code does not test for the 
								// existance of multiple wild cards. The single wild card is expected to be the last character in the generic
								// target name).
								//   
								// Get the index of the * wildcard (if any) in the target environment name currently being processed. 
								wildcardIndex = dtAttrs.targetEnvName.indexOf('*')
								
								// Check if the target environment name currently being processed is a generic target environment name.
								// An index value of >=0 means we have found a wildcard.
								if (wildcardIndex >= 0) {
									// Ensure that the wildcard is the last character in the generic target environment name.
									if (dtAttrs.targetEnvName.length() > (wildcardIndex + 1)) {
										println '** ERROR: Generic target environment name is not valid. Generic target environment names must end with an * wildcard **\n'
										throw new IllegalArgumentException('Generic target environment name is not valid')
									}
									
									// If the wildcardIndex is 0, the (generic) target environment name consists of just the * wildcard.
									if (wildcardIndex == 0) {
										// Remember just the * wildcard.
										genericTargetEnvironmentName = dtAttrs.targetEnvName
									} else {
										// The (generic) target environment name consists of more characters than just the * wildcard so, we check if the 
										// additional characters are an exact subset of the targetEnvironmentName. If they are, and we do not find any suitable
										// specific overrides, we can use the overrides defined for this (generic) target Environment name.
										if (targetEnvironmentName.take(wildcardIndex) == dtAttrs.targetEnvName.take(wildcardIndex)) {
											genericTargetEnvironmentName = dtAttrs.targetEnvName
										}										
									}									
								}
							}
						}
							
						// If we did not find any specific overrides for the current ressource being processed, check if any suitable generic overrides 
						// have been specified or not. If they have, we'll use them. Otherwise, there are no suitable overrides for the current resource
						// being processed.
						if (!specificOverridesFound) {
							// Check if we have suitable generic overrides.
							if (genericTargetEnvironmentName != '') {
								// We have suitable generic overrides so let's locate and apply them.
								// Loop for each deployment target.
								resourceAttr.value.each { dtAttrs->
									// If the deployment target equals the generic target environment name and override attributes are to be deployed,
									// generic overrides have been found so we process the generic override attributes.
									if (dtAttrs.targetEnvName == genericTargetEnvironmentName && dtAttrs.deploy == true) {
										if (trace) {
											println ' Data: Generic Overrides Found, genericTargetEnvironmentName = ' + genericTargetEnvironmentName
											println ' Data: overrideResourceKey: ' + overrideResourceKey
										}

										// Loop for each group of attributes.
										dtAttrs.each { dtAttrsGrp->
											if (trace) {
												println ' Data: dtAttrsGrp: ' + dtAttrsGrp + ' dtAttrsGrp.value.getClass(): ' + dtAttrsGrp.value.getClass()
											}
		
											// Add the key to the overrideResourcesMap if it has not already been added.
											if (!overrideResourcesMap.containsKey(overrideResourceKey)) {
												overrideResourcesMap.put(overrideResourceKey, true)
											}
		
											// All override attributes must be specified within their containing attribute groups so we
											// expect them to be in a HashMap.
											String dtAttrClass = dtAttrsGrp.value.getClass().toString()
											if (dtAttrClass.contains(hashMapClass) || dtAttrClass.contains(lazyMapClass))  {
												// Loop for each attribute in the attribute group.
												dtAttrsGrp.value.each { dtAttr->
													// Add the attribute override value to the overrides map.
													overridesMap.put('command:' + resourceAttrs.command + '::name:' + resourceAttrs.name + '::typeOfResource:' + typeOfResource.key + '::type:' + resourceAttrs.type + '::attrGrp:' + dtAttrsGrp.key + '::attrName:' + dtAttr.key, 'attrValue:' + dtAttr.value)
												}
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}

		// Create a list of the override maps so that we can pass them back. If the overrides file contains the empty set {},
		// the overrideResourcesMap and the overridesMap will both be empty as there will be no resources to deploy.
		// Note: A Tuple2 was not used here because the minimum version of groovy supported by UCD does not support Tuple2.
		if (overridesMap.size() == 0) {
			println '** INFORMATION: No resources to deploy in overrides file **'
		}
		
		def overridesMaps = [overrideResourcesMap, overridesMap]

		if (trace) {
			println ' Data: overridesMaps: ' + overridesMaps
			println ' Data: overrideResourcesMap: ' + overrideResourcesMap
			println ' Data: overridesMap: ' + overridesMap
			println 'Exit : buildOverridesMap'
		}

		// Pass back the overrides maps.
		return overridesMaps
	}

	/**
	 * Merge the MQ resource definitions in the base file and in the overrides map to form
	 * a merged map of name value pairs.
	 *
	 * @param base - map containing the base MQ resource definitions.
	 * @param overridesMaps - list containing a map of the resources to be deployed and a map containing the override attributes and values as name value pairs.
	 * @return mergedMap - map containing the merged base and override attributes and values as name value pairs.
	 */
	def private mergeBaseAndOverrides(base, overridesMaps) {

		if (trace) {
			println 'Entry: mergeBaseAndOverrides'
			println ' Data: base: ' + base
			println ' Data: overridesMaps: ' + overridesMaps
		}

		// Note: For efficiency, variables are declared outside of the each loops.
		// Map for override resources to be deployed.
		def overrideResourcesMap = new HashMap()
		// Get override resources map.
		overrideResourcesMap += overridesMaps[0]
		// Map for overrides.
		def overridesMap = new HashMap()
		// Get overrides map.
		overridesMap += overridesMaps[1]
		// Map to hold merged output.
		def mergedMap = new HashMap()
		// Resource key.
		def resourceKey = ''
		// Resource attribute key.
		def resourceAttrKey = ''
		// Optional Attribute resource attribute key.
		def optionalAttrResourceAttrKey = ''
		// Key for adding attributes to the merged map. 
		def mergedMapAttrKey = ''
		// Attribute value.
		def attrValue = ''
		// Flag to indicate whether a resource attribute for a given resource has been written to the merged map or not.
		def resourceAttributeWritten
		// Optional Attributes Map.
		def optionalAttrsMap = [noForce:'force', force:'noForce', noPurge:'purge', purge:'noPurge', noReplace:'replace', replace:'noReplace']
				
		// Merge the base and the override attributes and put the merged result to a merged map.
		// Loop for each type of resource (currently queue or channel).
		base.resource.each { typeOfResource->
			// Loop for each resource of the resource type (currently queue or channel).
			typeOfResource.value.each { resourceAttrs->
				// Build the resource key from the resource in the base file.
				resourceKey = 'command:' + resourceAttrs.command + '::name:' + resourceAttrs.name + '::typeOfResource:' + typeOfResource.key + '::type:' + resourceAttrs.type

				// If the overrides resources map contains the resource key, we deploy the resource so we proceed to
				// process it. Otherwise, we will skip the resource.
				if (overrideResourcesMap.containsKey(resourceKey)) {
					// Indicate that an attribute for the current resource has not yet been written to the merged map.
					resourceAttributeWritten = false
					// Loop for each resource attribute of the resource being processed.
					resourceAttrs.each { resourceAttr->
						if (trace) {
							println ' Data: resourceAttr.value:' + resourceAttr.value + ' resourceAttr.value.getClass(): ' + resourceAttr.value.getClass()
						}

						// Has a resource attribute group been specified in the base file ?
						String resourceAttrClass = resourceAttr.value.getClass().toString()
						if (resourceAttrClass.contains(hashMapClass) || resourceAttrClass.contains(lazyMapClass)) {
							if (resourceAttr.value.size() == 0) {
								println '** ERROR: No attributes found in resource attribute group ' + resourceAttr.key + '**\n'
								throw new IllegalArgumentException('No attributes found in resource attribute group')
							}
							
							// Yes, we have a resource attribute group so we'll process each attribute in the group.
							resourceAttr.value.each { resAttr->
								// Create the resource attribute key for storing into the merged map.
								resourceAttrKey = 'command:' + resourceAttrs.command + '::name:' + resourceAttrs.name + '::typeOfResource:' + typeOfResource.key + '::type:' + resourceAttrs.type + '::attrGrp:' + resourceAttr.key + '::attrName:' + resAttr.key

								if (trace) {
									println ' Data: resourceAttrKey: ' + resourceAttrKey
								}
		
								// If the attribute being processed from the base file also has an override value, process the override now.
								// For single keyword optional attributes (e.g. force, noForce, purge, noPurge, replace, noReplace), the 
								// keyword may have been specified in both the base and the overrides files. 
								if (overridesMap.containsKey(resourceAttrKey)) {
									mergedMapAttrKey = resourceAttrKey
									
									if (trace) {
										println ' Data: mergedMapAttrKey 1: ' + mergedMapAttrKey
									}
									
									attrValue = overridesMap.get(resourceAttrKey)
									// Remove the processed entry from the overridesMap.
									overridesMap.remove(resourceAttrKey)
								}
								// If the attribute being processed from the base file is an optional attribute, we check if the opposite
								// value for the optional attribute (e.g. noForce instead of force) has been specified in the overrides file. 
								else if (optionalAttrsMap.containsKey(resAttr.key)) {
									// Create the resource attribute key for the opposite optional attribute value for storing into the merged map.
									optionalAttrResourceAttrKey = 'command:' + resourceAttrs.command + '::name:' + resourceAttrs.name + '::typeOfResource:' + typeOfResource.key + '::type:' + resourceAttrs.type + '::attrGrp:' + resourceAttr.key + '::attrName:' + optionalAttrsMap.get(resAttr.key)
									
									if (trace) {
										println ' Data: optionalAttrResourceAttrKey: ' + optionalAttrResourceAttrKey
									}

									// If the opposite value for an optional attribute is in the overrides file, we process the override value now.
									if (overridesMap.containsKey(optionalAttrResourceAttrKey)) {
										mergedMapAttrKey = optionalAttrResourceAttrKey
									
										if (trace) {
											println ' Data: mergedMapAttrKey 2: ' + mergedMapAttrKey
										}
										
										attrValue = overridesMap.get(optionalAttrResourceAttrKey)
										// Remove the processed entry from the overridesMap.
										overridesMap.remove(optionalAttrResourceAttrKey)
									}
									else {
										//Otherwise, we'll just use the attribute value from the base file.
										mergedMapAttrKey = resourceAttrKey
										
										if (trace) {
											println ' Data: mergedMapAttrKey 3: ' + mergedMapAttrKey
										}
											
										attrValue = 'attrValue:' + resAttr.value
									}
								}
								else {
									// Otherwise, we'll just use the attribute value from the base file.
									mergedMapAttrKey = resourceAttrKey
									
									if (trace) {
										println ' Data: mergedMapAttrKey 4: ' + mergedMapAttrKey
									}
									
									attrValue = 'attrValue:' + resAttr.value
								}

								// Write attribute to the merged map.
								mergedMap.put(mergedMapAttrKey, attrValue)
								// Indicate that an attribute for the resource has been written.
								resourceAttributeWritten = true
							}
						}
					}

					// If a resource attribute group was not specified (in the base file) for the current resource being processed,
					// we will not have written anything about the current resource to the merged map yet. So, we'll write it now.
					// Also note that if there are no resource attribute groups specified in the base file, we cannot process any overrides
					// for this resource as all override values are expected to be defined within a resource group.
					if (!resourceAttributeWritten) {
						// Create the resource attribute key for storing in to the merged map.
						mergedMapAttrKey = 'command:' + resourceAttrs.command + '::name:' + resourceAttrs.name + '::typeOfResource:' + typeOfResource.key + '::type:' + resourceAttrs.type
						
						if (trace) {
							println ' Data: mergedMapAttrKey 5: ' + mergedMapAttrKey
						}
						
						// Write attribute to the merged map.
						mergedMap.put(mergedMapAttrKey, "")
					}
				}
			}
		}

		// In case there are still attributes in the overrides map, merge these (it is possible that attribute
		// override values were specified in the overrides file but not in the base definition file).
		mergedMap += overridesMap

		if (trace) {
			println ' Data: mergedMap: ' + mergedMap
			println 'Exit : mergeBaseAndOverrides'
		}

		// Pass back the merged map.
		return mergedMap
	}

	/**
	 * Substitute any property values in the merged map.
	 *
	 * @param propertiesFile - file containing property values (in JSON form) that are to be used to replace tokens in the MQ resource definitions.
	 * @param mergedMap - map containing the base and overrides merged resource definitions.
	 * @return mergedMapWithPropertiesSet - map containing the base and overrides merged resource definitions with the properties set.
	 */
	def private substituteProperties(propertiesFile, mergedMap) {

		if (trace) {
			println 'Entry: substituteProperties'
			println ' Data: propertiesFile: ' + propertiesFile
			println ' Data: mergedMap: ' + mergedMap
		}
		// Note: For efficiency, variables are declared outside of the each loops.
		// Read the properties file.
		def properties = parseFile(propertiesFile)

		// Field to hold the attribute key.
		def attrKey = ''
		// Field to hold the attribute value.
		def attrValue = ''

		if (trace) {
			println ' Data: Properties: ' + properties
		}

		// Define a new map for the merged attributes with the properties set.
		def mergedMapWithPropertiesSet = [:]

		// Loop to replace property tokens in each attribute value.
		mergedMap.each { mergedMapEntry->
			// Get the key and value fields of each attribute in the merged map.
			attrKey = mergedMapEntry.key
			attrValue = mergedMapEntry.value

			// Loop through each property value.
			properties.each { props->
				// Replace any tokens (specified within '@' symbols) in the attribute key and value fields.
				// Note: We cumulatively replace each property value that may exist in the attribute key and/or value.
				attrKey = attrKey.replaceAll('@' + props.key + '@', props.value)
				attrValue = attrValue.replaceAll('@' + props.key + '@', props.value)
			}

			// Save the updated attribute key and value.
			mergedMapWithPropertiesSet.put(attrKey, attrValue)
		}

		if (trace) {
			println ' Data: mergedMapWithPropertiesSet: ' + mergedMapWithPropertiesSet
		}

		// By this time, we should have replaced all tokens but we'll check if any were not replaced.
		// It is possible that Token Value pairs for some tokens were not specified in the properties file.
		// If this is the case, we throw an exception now as there is no point in passing MQSC commands for
		// resources that contain tokens since MQ will simply reject these.
		if (mergedMapWithPropertiesSet.toString().contains('@')) {
            println '\n'
			throw new IllegalArgumentException('Unresolved tokens found wrapped between \'@\' characters. Ensure all Tokens and Values are defined in the properties file')
		}

		if (trace) {
			println 'Exit : substituteProperties'
		}

		// Pass back the merged map with the properties set.
		return mergedMapWithPropertiesSet
	}

	/**
	 * Process any override resource names that may have been specified in the overrides file, and
	 * return a sortedMergedMap with all resource attributes and with those resource names resolved.
	 *
	 * @param mergedMap - map containing the merged base and override attributes and values as name value pairs.
	 * @return sortedMergedMap - map containing the sorted merged base and override attributes and values (with override name values) as name value pairs.
	 */
	def private processOverrideNames(mergedMap) {

		if (trace) {
			println 'Entry: processOverrideNames'
			println ' Data: mergedMap: ' + mergedMap
		}

		// Sort merged map.
		Map<String, String> sortedMergedMap = new TreeMap<String, String>(mergedMap)

		if (trace) {
			println ' Data: sortedMergedMap: ' + sortedMergedMap
		}

		// Map to hold the resource names that are to be overridden.
		Map<String, String> overrideNamesMap = new TreeMap<String, String>()

		def overrideNameIndex = 0

		// Locate entries for any resource names that are to be overridden and put these
		// into the overrideNamesMap.
		sortedMergedMap.each {sortedMergedMapKey, sortedMergedMapValue ->
			// Get the index, in the key, to the overrideName ?
			overrideNameIndex = sortedMergedMapKey.toString().indexOf('::attrGrp:overrideName')

			// If there is a key for the overrideName in the sortedMergedMap, put the override name
			// key and value into the overrideNamesMap.
			if (overrideNameIndex > 0) {
				overrideNamesMap.put(sortedMergedMapKey, sortedMergedMapValue)
			}
		}

		// Temporary map to hold the merged data as we replace override names.
		Map<String, String> tempMergedMap = new TreeMap<String, String>()

		// If we did locate some overrideName entries, we'll remove these from the sortedMergedMap.
		// Note, this needs to be done outside of the above loop to avoid a ConcurrentModificationException.
		if (overrideNamesMap.size() > 0) {
			overrideNamesMap.each {overrideNamesMapKey, overrideNamesMapValue ->
				// Remove the overrideName entries.
				sortedMergedMap.remove(overrideNamesMapKey)
			}

			if (trace) {
				println ' Data: sortedMergedMap: ' + sortedMergedMap
				println ' Data: overrideNamesMap: ' + overrideNamesMap
			}

			// Index values used to locate and replace the resource name with the override name.
			def comparisonKeyIndex = 0
			def nameValueStartIndex = 0
			def typeOfResourceFieldStartIndex = 0
			def overrideNameValueStartIndex = 0

			// New key that will contain the overridden name for the resoure.
			def newMergedMapKey = ''

			// Loop for each resoure name in the override map.
			overrideNamesMap.each {overrideNameMapKey, overrideNameMapValue ->
				// Start with an empty temporary merged map.
				tempMergedMap.clear()

				// For each resource attribute entry in the sorted merged map, check if the value of the name in
				// the key needs to be replaced with the value of the override name, and replace it if required.
				sortedMergedMap.each {sortedMergedMapKey, sortedMergedMapValue ->
					if (trace) {
						println ' Data: overrideNameMapKey: ' + overrideNameMapKey + ' overrideNameMapValue: ' + overrideNameMapValue + ' sortedMergedMapKey: ' + sortedMergedMapKey + ' sortedMergedMapValue: ' + sortedMergedMapValue
					}

					// Get the index to the part of the resource attribute key that we will use for camparison.
					comparisonKeyIndex = overrideNameMapKey.toString().indexOf('::type:')

					// Compare part of the keys in the resource attribute entries in both maps.
					// If they are equal, we'll replace the value of the name in the key with the value of the override name.
					if (sortedMergedMapKey.toString()[0..comparisonKeyIndex] == overrideNameMapKey.toString()[0..comparisonKeyIndex]) {
						// Get the index to the start of the name value in the sorted merged map key.
						nameValueStartIndex = sortedMergedMapKey.toString().indexOf('::name:') + '::name:'.size() - 1
						// Get the index to the start of the type of resource field.
						typeOfResourceFieldStartIndex = sortedMergedMapKey.toString().indexOf('::typeOfResource:')
						// Get the index to the start of the override name value in the override map.
						overrideNameValueStartIndex = overrideNameMapValue.toString().indexOf('attrValue:') + 'attrValue:'.size()
						// Construct the new key with the override name value in it.

						if (trace) {
							println ' Data: nameValueStartIndex: ' + nameValueStartIndex + ' typeOfResourceFieldStartIndex: ' + typeOfResourceFieldStartIndex + ' overrideNameValueStartIndex: ' + overrideNameValueStartIndex
					 	}

						newMergedMapKey = sortedMergedMapKey.toString()[0..nameValueStartIndex] +
						         		  overrideNameMapValue.toString()[overrideNameValueStartIndex..overrideNameMapValue.size()-1] +
										  sortedMergedMapKey.toString()[typeOfResourceFieldStartIndex..sortedMergedMapKey.size()-1]

						// Put the new key with the original value to the temporary map.
						tempMergedMap.put(newMergedMapKey, sortedMergedMapValue)

						if (trace) {
							println ' Data: tempMergedMap: ' + tempMergedMap
						}
					}
					else {
						// If the keys are not equal, just put the original key and value to the temporary map.
						tempMergedMap.put(sortedMergedMapKey, sortedMergedMapValue)
					}
				}

				// Clear the sortedMergedMap and set it to the temporaryMergedMap which has had some name values replaced with the
				// override name values. We'll then loop to process any further override names.
				sortedMergedMap.clear()
				sortedMergedMap += tempMergedMap
			}
		}

		if (trace) {
			println ' Data: sortedMergedMap: ' + sortedMergedMap
			println 'Exit : processOverrideNames'
		}

		// Pass back the sorted merged map.
		return sortedMergedMap
	}

	/**
	 * Merge the base and overrides resource definitions.
	 *
	 * @param baseFileName - name of file containing the base MQ resource definitions.
	 * @param agentWorkDir - the UCD agent's work directory. Temporary files are created in here.
	 * @param mergedMap - map containing the merged base and overrides attribute and values as name value pairs.
	 * @return mergedData - string variable that contains the merged base and overrides resource definition (with properties replaced too).
	 */
	def private mergeDefinition(baseFileName, agentWorkDir, mergedMap) {

		if (trace) {
			println 'Entry: mergeDefinition'
			println ' Data: baseFileName: ' + baseFileName
			println ' Data: agentWorkDir: ' + agentWorkDir
			println ' Data: mergedMap: ' + mergedMap
		}

		// Number of entries in Map, used to decide if a newline char should be added or not.
		def numInMap = 0
		// String variable to hold the merged base and overrides data.
		def mergedData = ''

		// Write the sorted merged output as name(value) pairs, separated by commas, to the merged data variable.
		mergedMap.each { resourceAttr->
			mergedData += (resourceAttr.key.replaceAll('::', ',') + ',' + resourceAttr.value)

			// If not processing the last line in the map, add a new line char.
			if (++numInMap < mergedMap.size())  {
				mergedData += '\n'
			}
		}

		if (trace) {
			println ' Data: mergedData: ' + mergedData
			println 'Exit : mergeDefinition'
		}

		// Pass back the merged data.
		return mergedData
	}

	/**
	 * Gets the full path (including the file name) to the mqResourceAttributes.mappings file.
	 *
	 * @return resAttrsFilePath - the full file path (including the file name) to the mqResourceAttributes.mappings file.
	 */
	def private getResourceAttributesFilePath() {

		if (trace) {
			println 'Entry: getResourceAttributesFilePath'
		}

		// Get the home directory for the plugin
		final def pluginHome = System.getenv('PLUGIN_HOME')

		def resAttrsFilePath = pluginHome + File.separator + 'resourceMappings' + File.separator + 'mqResourceAttributes.mappings'

		if (trace) {
			println ' Data: PLUGIN_HOME: ' + pluginHome
			println ' Data: Full file path to resource attributes file: ' + resAttrsFilePath
			println 'Exit : getResourceAttributesFilePath'
		}

		// Return the full path to the mqResourceAttributes.mappings file
		return resAttrsFilePath
	}

	/**
	 * Parse the JSON form resource attributes and return them, sorted in alphabetical order, in a map.
	 *
	 * @param resourceAttributesFileName - the name of the file which contains that JSON form of the resource attributes
	 * @return sortedResourceAttributesMap - map containing resource attributes sorted in alphabetical order
	 */
	def private parseResourceAttrs(resourceAttributesFileName) {

		if (trace) {
			println 'Entry: parseResourceAttrs'
			println ' Data: resourceAttributesFileName: ' + resourceAttributesFileName
		}

		// Define the resource attribute mappings file
		def resourceAttributesFile = new File(resourceAttributesFileName)
		// Parse the JSON format resource attribute mappings file into a map
		def resourceAttributes = parseFile(resourceAttributesFile)

		if (trace) {
			println ' Data: resourAttributes: ' + resourceAttributes
		}

		// Note: For efficiency, variables are declared outside of the each loops.
		def resourceAttributesMap = [:]
		def key = ''
		def value = ''

		// Loop for each resource type.
		resourceAttributes.resource.each { typeOfResource->
			// Loop for each resource within a given resource type.
			typeOfResource.value.each { resourceAttrs->
				// Loop for each group of resource attributes within a given resource.
				resourceAttrs.each { resourceAttrGrp->

					if (trace) {
						println ' Data: resourceAttrGrp.value' + resourceAttrGrp.value + ' resourceAttrGrp.value.getClass(): ' + resourceAttrGrp.value.getClass()
					}
					String resourceAttrGrpClass = resourceAttrGrp.value.getClass().toString()
					if (resourceAttrGrpClass.contains(hashMapClass) || (resourceAttrGrp.value.getClass() in java.lang.String) || resourceAttrGrpClass.contains(lazyMapClass)) {
						// Loop for each attribute within a group of resource attributes.
						resourceAttrGrp.value.each { resourceAttr->
							//Key consists of the resource type, the resource group name and the attribute name.
							key = typeOfResource.key + '::' + resourceAttrGrp.key + '::' + resourceAttr.key

							// Value contains the attribute data type and the MQSC form attribute mapping name.
							value = resourceAttr.value

							// Write attribute as (keyed) name value pair to the resource attributes map. We
							// essentially write the minimum amount of data we need to map the REST attribute name
							// to the MQSC attribute name. We'll use this later when we actually do the mapping.
							resourceAttributesMap.put(key, value)
						}
					}
				}
			}
		}

		// Sort the resource attributes map.
		Map<String, String> sortedResourceAttributesMap = new TreeMap<String, String>(resourceAttributesMap)

		if (trace) {
			println ' Data: resourceAttributesMap: ' + resourceAttributesMap
			println ' Data: sortedResourceAttributesMap: ' + sortedResourceAttributesMap
			println 'Exit : parseResourceAttrs'
		}

		// Pass back sorted resource attributes map.
		return sortedResourceAttributesMap
	}

	/**
	 * Generate the MQSC form resource definitions.
	 *
	 * @param mergedData - file that contains the base and overrides merged data.
	 * @param sortedResourceAttrsMap - map containing all the resource attributes sorted in alphabetical order.
	 * @param mqscResourceDefinitions - property variable to which the MQSC form of the resource definitions are written.
	 * @return mqscResourceDefinitions - property variable to which the MQSC form of the resource definitions are written.
	 */
	def private generateMQSCDefinitions(mergedData, sortedResourceAttrsMap, mqscResourceDefinitions) {

		if (trace) {
			println 'Entry: generateMQSCDefinitions'
			println ' Data: mergedData: ' + mergedData
			println ' Data: sortedResourceAttrsMap: ' + sortedResourceAttrsMap
			println ' Data: mqscResourceDefinitions: ' + mqscResourceDefinitions
		}

		// Note, this is for future work, if we decide to support both z/OS and distributed MQ platforms.
		// Determine if we are running on the z/OS platform. If not, assume we are running on a distributed platform.
		// def osName = System.getProperty('os.name').toLowerCase(Locale.US)
		// def isZos = osName.indexOf('z/os') > -1 || osName.indexOf('os/390') > -1

		// Supported commands (used for validation and to display in a message)
		// Note: As more commands are supported, just add them, within single quotes and separated by commas, to this array.
		def supportedCommands = ['DEFINE', 'DELETE'] as String[]

		// Supported queue types.
		def supportedQueueTypes = [alias: 'ALIAS', local: 'LOCAL', model: 'MODEL', remote: 'REMOTE']

		// Supported channel types.
		def supportedChannelTypes = [amqp: 'AMQP', clientConnection: 'CLNTCONN', clusterReceiver: 'CLUSRCVR', clusterSender: 'CLUSSDR', receiver: 'RCVR', requester: 'RQSTR', sender: 'SDR', server: 'SVR', serverConnection: 'SVRCONN']

		// Note: For efficiency, variables are declared outside of each loops.
		// Map for processing attributes.
		def attrMap = new HashMap()
		// Name value pairs.
		def nameValue = ''
		// MQSC command
		def command = ''
		// Previous MQSC command
		def previousCommand = ''
		// Name of next resource to be processed.
		def nextResName = ''
		// Type of next resource to be processed.
		def nextResType = ''
		// Subtype of next resource to be processed.
		def nextResSubType = ''
		// Number of resources processed.
		def numOfResources = 0
		// Map to contain the attribute data type, name and/or value.
		def mqscNameValueMap = [:]

		// Read each line of the merged data. Each line contains details for one attribute.
		mergedData.eachLine { line, number->
			// The attrMap holds the properties (e.g. name, typeOfResource, type, attrName, attrGrp, attrValue)
			// of an attribute as name value pairs. Start with an empty map for each line.
			attrMap = [:]
			// Split, at commas, the data on the line into data portions.
			line.split(',').each { pairs ->
				// Split, at a colon, each portion of data into attribute name value pairs.
				nameValue = pairs.split(':', 2)

				// Note: The Groovy Elvis operator has not been used here as it can be unreliable.
				// If the value is null or "", set it to ''
				if (nameValue[1] == null | nameValue[1] == "") {
					nameValue[1] == ''
				}

				// Save each attribute name and value in the attribute map.
				attrMap[nameValue[0]] = nameValue[1]
			}

			// Check if we are about to process the next resource definition.
			if (nextResName != attrMap.name || nextResType != attrMap.typeOfResource || nextResSubType != attrMap.type) {
				// Save the previous command
				if (numOfResources < 1) {
					// If we are about to process the first resource, we'll set the previous command to the
					// command for the first resource. We do this incase we only have one resource to process.
					previousCommand = (String)attrMap.command
				}
				else {
					// If we have already processed a resource and are about to process the next resource, we'll
					// save the previous command before proceeding to get the command for the next resource.
					previousCommand = command
				}

				// Save the next resource's values.
				command = (String)attrMap.command
				assert supportedCommands.contains(command) : '** ERROR: Expected to find a supported command(i.e. ' + supportedCommands.join(',') + '), found ' + command + ' **'

				nextResName = attrMap.name
				nextResType = attrMap.typeOfResource
				nextResSubType = attrMap.type

				// Increment the count of resources.
				numOfResources++

				// If this is not the first resource.
				if (numOfResources > 1) {
					// End the last line of the previous resource and leave a blank comment line between the previous and the next
					// resource definition in the mqsc resource definitions property variable (just to make it look pretty).
					mqscResourceDefinitions += '\n*\n'
				}

				// Write the command (only DEFINE and DELETE supported at present) to the mqsc resource definitions property variable.
				mqscResourceDefinitions += command

				// Write resource type and name to the mqsc resource definitions property variable.
				switch(attrMap.typeOfResource) {
					case 'queue':
						// If the queue type has been left blank, assume a default queue type of LOCAL.
						if ((String)attrMap.type == "") {
							mqscResourceDefinitions += ' QLOCAL' + '(\'' + attrMap.name + '\')'
						}
						// Otherwise, use the supplied queue type.
						else {
							// Verify that the supplied queue type is one of the supported queue types.
							assert supportedQueueTypes.containsKey(attrMap.type) : '** ERROR: Expected to find a supported queue type (i.e. ' + supportedQueueTypes.keySet() + '), found ' + (String)attrMap.type + ' **'
							// Write Q, type and queue name.
							mqscResourceDefinitions += ' Q' + supportedQueueTypes.get(attrMap.type) + '(\'' + attrMap.name + '\')'
						}
						break
					case 'channel':
						// Write CHANNEL and channel name.
						mqscResourceDefinitions += ' CHANNEL' + '(\'' + attrMap.name + '\')'
						// Verify that the supplied channel type is one of the supported channel types.
						assert supportedChannelTypes.containsKey(attrMap.type) : '** ERROR: Expected to find a supported channel type (i.e. ' + supportedChannelTypes.keySet() + '), found ' + (String)attrMap.type + ' **'
						// Write the CHLTYPE.
						mqscResourceDefinitions = writeAttribute(mqscResourceDefinitions, command.size()+1, [attrDataType: 'keyword', attrName: 'CHLTYPE', attrValue: supportedChannelTypes.get(attrMap.type)])

						// If we are defining a channel then we'll set the transmission protocol type.
						if (command == 'DEFINE') {
							mqscResourceDefinitions = writeAttribute(mqscResourceDefinitions, command.size()+1, [attrDataType: 'keyword', attrName: 'TRPTYPE', attrValue: 'TCP'])
						}
						break
					default:
					 	println '** ERROR: generateMQSCDefinitions - Unexpected resource type found: ' + attrMap.typeOfResource + ' **\n'
						throw new IllegalArgumentException('Unexpected resource type found')
						break
				}
			}

			// Only proceed to convert the REST form attribute to MQSC form if we have an attribute within an attribute group.
			// Note: It is not an error if no attribute groups have been specified. Resources can be defined without any attribute 
			// groups having been specified.
			if (trace) {
				println ' Data: attrMap.attrGrp ' + attrMap.attrGrp
			}
			
			if (attrMap.attrGrp != null) {
		
				// Convert a REST form attribute to an MQSC form attribute.
				// The returned map contains the attribute data type, name and/or value.
				mqscNameValueMap = restToMQSCAttr(attrMap, sortedResourceAttrsMap)
		
				// KAINT (Keep Alive Interval) is declared as an integer attribute. It has a special value of -1 which 
				// equates to AUTO in the MQSC. So, if we determine that the value specified in the JSON representation 
				// is -1, we change it to AUTO here so that when we subsequently write the attribute to the output 
				// variable, it will appear as KAINT(AUTO) which is acceptable by the MQSC. AMQPKA (AMQP Keep Alive 
				// Interval is declared in the same way and needs to appear as AMQPKA(AUTO).
				if ((mqscNameValueMap.attrName == 'KAINT') && (mqscNameValueMap.attrValue == '-1') ||
					(mqscNameValueMap.attrName == 'AMQPKA') && (mqscNameValueMap.attrValue == '-1')) {
					mqscNameValueMap.attrValue = 'AUTO'
				}

				// Note: This could be enhanced in the future to write the attributes sorted in alphabetical order,
				// to the mqsc resource definitions property variable.
				//
				// Write attribute to mqsc resource definitions property variable.
				mqscResourceDefinitions = writeAttribute(mqscResourceDefinitions, command.size()+1, mqscNameValueMap)
			}
		}

		if (trace) {
			println ' Data: ' + mqscResourceDefinitions
			println 'Exit : generateMQSCDefinitions'
		}

		return mqscResourceDefinitions
	}

	/**
	 * Read file with a reader and parse with jsonSlurper
	 *
	 * @param fileName - name of file to be read
	 * @return parsedData - file contents returned as a map
	 */
	def private parseFile(fileName) {

		if (trace) {
			println 'Entry: parseFile'
			println ' Data: fileName: ' + fileName
		}

		// Get the file separator for the platform this script is running on.
		//def fs = File.separator

		// Map to hold the parsed data.
		def parsedData = [:]

		// Define jsonSlurper for parsing json.
		// Note: jsonSlurper.parse returns the data in alphabetical order because it uses Treemaps by default.
		def jsonSlurper = new JsonSlurper()

		// Read file with reader and parse the JSON data into a map. The files being read are
		// expected to be in UTF-8 encoding.
		fileName.withReader('utf-8') { fileReader->
			try {
				parsedData = jsonSlurper.parse(fileReader)
			}
			catch (NullPointerException npe) {
				println '** ERROR: NullPointerException encountered, file ' + fileName + ' may be empty.' + ' **\n'
				throw npe
			}
		}

		if (trace) {
			println ' Data: parsedData: ' + parsedData
			println 'Exit : parseFile'
		}

		// Return parsed data.
		return parsedData
	}

	/**
	 * Write attribute name and/or value (in string form) to mqsc resource definitions property variable.
	 *
	 * @param mqscResourceDefinitions - property variable to which the MQSC form of the resource definitions are written.
	 * @param noOfSpaces - number of spaces to write before writing the attribute name and/or value.
	 * @param mqscNameValueMap - map containing the MQSC format attribute datatype, name and/or value.
	 * @return mqscResourceDefinitions - property variable to which the MQSC form of the resource definitions are written.
	 */
	def private writeAttribute(mqscResourceDefinitions, noOfSpaces, mqscNameValueMap) {

		if (trace) {
			println 'Entry: writeAttribute'
			println ' Data: mqscResourceDefinitions: ' + mqscResourceDefinitions
			println ' Data: noOfSpaces: ' + noOfSpaces
			println ' Data: mqscNameValueMap: ' + mqscNameValueMap
		}

		// Write continuation character and new line character.
		mqscResourceDefinitions += ' +\n'

		// Write spaces before writing attribute (just padding to pretty up the layout).
		for (def i=0 ; i<noOfSpaces; i++) {
			mqscResourceDefinitions += ' '
		}

		// Write the attribute to the mqsc resource definitions property variable, based on the data type.
		switch(mqscNameValueMap.attrDataType) {
			case ['booleanFlag', 'flag']:
				// Just write the attribute value
				mqscResourceDefinitions += (String)mqscNameValueMap.attrValue
				break
			case ['int', 'keyword']:
				// Write the attribute name and value within brackets.
				mqscResourceDefinitions += (String)mqscNameValueMap.attrName + '(' + (String)mqscNameValueMap.attrValue + ')'
				break
			case ['string']:
				// Write the attribute name and value within brackets and within single quotes.
				//
				// Note: The MQ command processors do not allow an empty string to be specified. So, if the
				// value is an empty string, we need to set it to a space character within single quotes.
				if (mqscNameValueMap.attrValue == '') {
					mqscResourceDefinitions += (String)mqscNameValueMap.attrName + '(\' \')'
				}
				else {
					// If we have a value then we just write the name and value.
					mqscResourceDefinitions += (String)mqscNameValueMap.attrName + '(\'' + (String)mqscNameValueMap.attrValue + '\')'
				}
				break
			default:
				println '** ERROR: writeAttribute - Unexpected attribute data type found: ' + mqscNameValueMap.attrDataType + ' **\n'
				throw new IllegalArgumentException('Unexpected attribute data type found')
				break
		}

		if (trace) {
			println ' Data: ' + mqscResourceDefinitions
			println 'Exit : writeAttribute'
		}

		return mqscResourceDefinitions
	}

	/**
	 * Convert a REST form attribute name to its equivalent MQSC form attribute name.
	 *
	 * @param attrMap - map containing the properties of an attribute that is to be mapped from REST to MQSC form.
	 * @param sortedResourceAttrsMap - map containing all valid resource attributes sorted in alphabetical order.
	 * @return mqscNameValueMap - map containing the MQSC form attribute data type, name and/or value.
	 */
	def private restToMQSCAttr(attrMap, sortedResourceAttrsMap) {

		if (trace) {
			println 'Entry: restToMQSCAttr'
			println ' Data: attrMap: ' + attrMap
			println ' Data: sortedResourceAttrsMap: ' + sortedResourceAttrsMap
		}

		// MQSC form name value map.
		def mqscNameValueMap = [:]
		// Key of REST attribute to be converted to MQSC. Used to locate the REST attribute in the
		// sorted resource attributes map.
		def findAttrKey = attrMap.typeOfResource + '::' + attrMap.attrGrp + '::' + attrMap.attrName

		if (trace) {
			println 'Data: findAttrKey: ' + findAttrKey
		}

		// Check if the resource attributes map contains details of the attribute whose name is to be
		// converted from the REST form to the MQSC form.
		if (sortedResourceAttrsMap.containsKey(findAttrKey)) {
			// Attribute details found so let's copy the value (i.e. the attribute data type and the
			// MQSC form attribute mapping name).
			def resourceAttrMap = sortedResourceAttrsMap.get(findAttrKey)

			if (trace) {
				println 'Data: resourceAttrMap: ' + resourceAttrMap
			}

			// Keys to be used when creating the mqscNameValueMap.
			def attrKeys = ['attrDataType', 'attrName', 'attrValue']

			if (trace) {
				println 'Data: attrKeys: ' + attrKeys
			}

			// Add the attribute data type to the name value map.
			mqscNameValueMap.put(attrKeys[0], resourceAttrMap.attrDataType)

			// Field to store attribute value.
			def value = ''

			// Based on the value of the attribute data type, add the attribute name and/or value to the
			// name value map.
			switch(resourceAttrMap.attrDataType) {
				case 'booleanFlag':
					if (resourceAttrMap.attrValueMappings.containsKey(attrMap.attrValue)) {
						value = resourceAttrMap.attrValueMappings.get(attrMap.attrValue)
						mqscNameValueMap.put(attrKeys[2], value)
					}
					else {
						println '** ERROR: restToMQSCAttr - Unexpected attribute keyword value: ' + attrMap.attrValue + ' found **\n'
						throw new IllegalArgumentException('Unexpected attribute keyword value found')
					}
					break
				case 'flag':
					mqscNameValueMap.put(attrKeys[2], resourceAttrMap.attrMapping)
					break
				case ['int', 'string']:
					mqscNameValueMap.put(attrKeys[1], resourceAttrMap.attrMapping)
					mqscNameValueMap.put(attrKeys[2], attrMap.attrValue)
					break
				case 'keyword':
					if (resourceAttrMap.attrValueMappings.containsKey(attrMap.attrValue)) {
						value = resourceAttrMap.attrValueMappings.get(attrMap.attrValue)
						mqscNameValueMap.put(attrKeys[1], resourceAttrMap.attrMapping)
						mqscNameValueMap.put(attrKeys[2], value)
					}
					else {
						println '** ERROR: restToMQSCAttr - Unexpected attribute keyword value: ' + attrMap.attrValue + ' found **\n'
						throw new IllegalArgumentException('Unexpected attribute keyword value found')
					}
					break
				default:
					println '** ERROR: restToMQSCAttr - Unexpected attribute data type found: ' + resourceAttrMap.attrDataType + ' **\n'
					throw new IllegalArgumentException('Unexpected attribute data type found')
					break
			}
		}
		else {
			println '** ERROR: restToMQSCAttr - Unexpected attribute group or keyword found in attribute key: ' + findAttrKey + ' **\n'
			throw new IllegalArgumentException('Unexpected attribute group or keyword found')
		}

		if (trace) {
			println ' Data: mqscNameValueMap: ' + mqscNameValueMap
			println 'Exit : restToMQSCAttr'
		}

		// Return map containing the MQSC form attribute data type, name and/or value.
		return mqscNameValueMap
	}
}