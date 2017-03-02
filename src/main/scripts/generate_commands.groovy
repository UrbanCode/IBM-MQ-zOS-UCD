/*
 * <copyright
 * notice="lm-source"
 * years="2016,2017">
 * Licensed Materials - Property of IBM
 *
 * (C) Copyright IBM Corp. 2016, 2017 All Rights Reserved.
 * </copyright>
 */

import com.urbancode.air.AirPluginTool
import com.urbancode.air.plugin.mqzos.mqscutil

// generate_commands.groovy is the first method (groovy script) that is invoked when
// the Generate MQSC Commands plugin step is run as part of an IBM UCD process. It:
//
// - Reads and validates the input variables,
// - Validates that the required files exist,
// - Creates the mqsc resource definitions property variable that contains the MQSC commands,
// - Writes the user data (set to Component and Version names by default) to the mqsc resource definitions property variable.
// - For all triplet of files in the work directory that meet the base file name filter criteria,
//   It verifies that the files exit, creates an instance of class mqscutil and invokes method
//   GenerateMQSCFormDefintions to generate the MQSC form of MQ commands for the REST form of MQ
//   Commands represented in the triplet of files, and to write them to the mqsc resource definitions property variable.
// - Displays details of the number of base definition files (and hence triplets of files) processed.
// - If no files are processed, a suitable exception is thrown.
// - If any exceptions are thrown, terminate with an return code of 1 (FAIL).
// - If base files are processed and no exceptions are thrown, output the mqsc resource definitions property
//   variable (this will be used by the next step, i.e. the submit job step, in the IBM UCD process) and
//   terminate with a return code of 0 (SUCCESS).
try {
	// Declare local variables.
	def apTool = ''
	final def workDir
	def props = ''
	def fileSubFolders = ''
	def baseFileNameFilter = ''
	def overridesFileType = ''
	def propertiesFileType = ''
	def targetEnvironmentName = ''
	def userData = ''
	def traceEnabled = false

	// Running as a UCD plugin ?
	if (System.getenv('AGENT_HOME') != null ){
		// Get the UCD agents work directory.
		workDir = new File('.').canonicalFile

		// Use the UCD air plug=in tool for reading or writing the step's input or output variables, respectively.
		apTool = new AirPluginTool(this.args[0], this.args[1])
		props = apTool.getStepProperties()

		// Get all properties for the plug-in. Trim any leading and trailing spaces.
		fileSubFolders = props['fileSubFolders']?.trim()
		baseFileNameFilter = props['baseFileNameFilter']?.trim()
		overridesFileType = props['overridesFileType']?.trim()
		propertiesFileType = props['propertiesFileType']?.trim()
		targetEnvironmentName = props['targetEnvironmentName']?.trim()
		userData = props['userData']?.trim()
		traceEnabled = props['traceEnabled']?.trim()
	}
	else {
		println '** WARNING: Owing to dependencies on UCD runtime properties, this code can only be run as a UCD plugin **'
		return
	}

	// Trace method entry and input variables.
	if (traceEnabled) {
		println 'Entry: generate_commands.groovy'
		println ' Data: fileSubFolders: ' + fileSubFolders
		println ' Data: baseFileNameFilter: ' + baseFileNameFilter
		println ' Data: overridesFileType: ' + overridesFileType
		println ' Data: propertiesFileType: ' + propertiesFileType
		println ' Data: targetEnvironmentName: ' + targetEnvironmentName
		println ' Data: userData: ' + userData
	}

	// Throw an exception if one of the required fields has not been supplied.
	if (!fileSubFolders || fileSubFolders.length() < 1) {
		throw new IllegalArgumentException('The name of at least one sub folder that contains the files to be processed must be specified')
	}
	if (!baseFileNameFilter || baseFileNameFilter.length() < 1) {
		throw new IllegalArgumentException('Base File Name Filter must be specified')
	}
	if (!overridesFileType || overridesFileType.length() < 1) {
		throw new IllegalArgumentException('Overrides File Type must be specified')
	}
	if (!propertiesFileType || propertiesFileType.length() < 1) {
		throw new IllegalArgumentException('Properties File Type must be specified')
	}
	if (!targetEnvironmentName || targetEnvironmentName.length() < 1) {
		throw new IllegalArgumentException('The target environment could not be found. The target environment may not be set up correctly')
	}
	if (!userData || userData.length() < 1) {
		println 'INFORMATION: The user data field has not been set.'
	}

	// Initialise some variables.
	def countOfTotalBaseFiles = 0, countOfTotalBaseFilesProcessed = 0
	def countOfBaseFilesInSubDirectory = 0, countOfBaseFilesInSubDirectoryProcessed = 0
	def baseFileNameAndType = ''
	def baseFileName = ''
	def baseFileType = ''
	def overridesFileNameAndType = ''
	def propertiesFileNameAndType = ''

	// Get the UCD agent's work directory.
	final def agentWorkDir = new File('.').canonicalFile

	// MQSC resource definitions property variable.
	def mqscResourceDefinitions = ''

	// Write out the userData (set to the Component and version names by default) as a comment in the MQSC resource
	// definition property variable. It may be useful to know the name and version of the component that generated
	// the MQSC.
	//
	// Note:
	// 1) Data for one job may be composed from more than one set of triplet files.
	// 2) The Job card and CSQUTIL JCL is added by the submit job step in the UCD process.
	mqscResourceDefinitions += '*\n* ' + 'User Data: ' + userData + '\n*'

	// Get list of sub folders.
	def fileSubFoldersList = fileSubFolders.split("\n");
	// Sub directory of each sub folder.
	def subDirectory = ''
	// Full path for the work directory (including the sub folder).
	def fullWorkDirPath = ''

	// Loop for each sub folder in the list.
	fileSubFoldersList.each {fileSubFolder ->
		// Remove any leading or trailling spaces from the sub folder name.
		fileSubFolder = fileSubFolder.trim()

		// If the sub folder is empty, we'll just issue a warning message and proceed to check the next sub folder.
		if (fileSubFolder.isEmpty()) {
			println 'WARNING: Sub folder ${fileSubFolder} is empty.'
		}
		// There are files in the sub folder so we'll proceed to process them.
		else {
			// Construct the full path for the work directory (i.e. include the sub folder).
			fullWorkDirPath = workDir.canonicalPath + File.separator + fileSubFolder

			if (traceEnabled) {
				println 'Data: fullWorkDirPath: ' + fullWorkDirPath
			}

			// Sub directory for the current sub folder.
			subDirectory = new File(fullWorkDirPath)

			// If the sub directory exists, process any files in the sub directory.
			if (subDirectory.exists()) {
				// Reset count of base files in the sub directory.
				countOfBaseFilesInSubDirectory = 0
				countOfBaseFilesInSubDirectoryProcessed = 0

				// Process each file in the full path of the work directory that meets the base file name filter criteria.
				new File(fullWorkDirPath).eachFileMatch(~baseFileNameFilter) { baseFile ->
					// Keep a count of the number of base files in the current sub directory.
					countOfBaseFilesInSubDirectory ++
					// Keep a count of the total number of base files that meet the filter criteria.
					countOfTotalBaseFiles ++

					// Validate the file names and types.
					// Copy the file name and type
					baseFileNameAndType = baseFile.name

					// Find the index of the '.' in the file name and obtain the file name and type.
					if (baseFileNameAndType.lastIndexOf('.') > 0) {
						baseFileName = baseFileNameAndType.substring(0, baseFileNameAndType.lastIndexOf('.'))
						baseFileType = baseFileNameAndType.substring(baseFileNameAndType.lastIndexOf('.') + 1, baseFileNameAndType.length())

						if (baseFileName.length() < 1) {
							throw new IllegalArgumentException('Base File Name error. A valid Base File Name must be specified')
						}

						if (baseFileType.length() < 1) {
							throw new IllegalArgumentException('Base File Type error. A valid Base File Type must be specified')
						}
					}
					else {
						throw new IllegalArgumentException('Base File Name or Type error. Base File Name and Type must be in the format <fileName>.mqdef_base')
					}

					if (overridesFileType.length() < 1) {
						throw new IllegalArgumentException('Overrides File Type error. A valid Overrides File Type must be specified')
					}
					else {
						// Deduce the overrides file name from the base file name and the overrides file type.
						overridesFileNameAndType = baseFileName + '.' + overridesFileType
					}

					if (propertiesFileType.length() < 1) {
						throw new IllegalArgumentException('Properties File Type error. A valid Properties File Type must be specified')
					}
					else {
						// Deduce the properties file name from the base file name and the properties file type.
						propertiesFileNameAndType = baseFileName + '.' + propertiesFileType
					}

					println 'INFORMATION: MQ definition base file: ' + baseFileNameAndType

					// If we cannot locate the overrides file, say so and return.
					if (!(new File(fullWorkDirPath, overridesFileNameAndType)).exists()){
						throw new FileNotFoundException('MQ definition overrides file ' + overridesFileNameAndType + ' not found')
					}
					else {
						println 'INFORMATION: MQ definition overrides file: ' + overridesFileNameAndType
					}

					// If we cannot locate the properties file, say so and return.
					if (!(new File(fullWorkDirPath, propertiesFileNameAndType)).exists()){
						throw new FileNotFoundException('MQ definition properties file ' + propertiesFileNameAndType + ' not found')
					}
					else {
						println 'INFORMATION: MQ definition properties file: ' + propertiesFileNameAndType
					}

					// Write the names of the triplet of files about to be read as comments to the MQSC resource definitions property variable.
					// If no commands are generated from the files, it is not a problem as only comments will be added to the variable.
					// Users may find it useful to know that files were read but no commands were generated for them. This could indicate a
					// resource definition error, or that residual files have not been cleaned up.
					mqscResourceDefinitions += '\n*\n* ' + baseFileNameAndType + ', ' + overridesFileNameAndType + ', ' + propertiesFileNameAndType + '\n*\n'

					// Call mqscutil to generate MQSC commands based on the 3 input files
					mqscResourceDefinitions = new mqscutil().generateMQSCFormDefinitions(
						baseFile,
						new File(fullWorkDirPath, overridesFileNameAndType),
						new File(fullWorkDirPath, propertiesFileNameAndType),
						mqscResourceDefinitions,
						targetEnvironmentName,
						traceEnabled)

					// Increment count of base files in sub directory processed.
					countOfBaseFilesInSubDirectoryProcessed ++
					// Increment count of base files processed.
					countOfTotalBaseFilesProcessed ++
				}

				println '** INFORMATION: ' + countOfBaseFilesInSubDirectory + ' base definition files found in sub directory ' + subDirectory + '.'
				println '** INFORMATION: ' + countOfBaseFilesInSubDirectoryProcessed + ' base definition files processed in sub directory ' + subDirectory + '.'

				if (countOfBaseFilesInSubDirectoryProcessed == 0) {
					println 'WARNING: Folder ' + subDirectory + ' does not contain any base files so no files were processed for this sub directory.'
				}
			}
			else {
				println 'WARNING: Folder ${subDirectory} does not exist.'
			}
		}
	}

	// If no files were found or processed, throw suitable exceptions.
	if (countOfTotalBaseFiles == 0) {
		throw new FileNotFoundException('No base definition files found matching base file name filter: ' + baseFileNameFilter)
	}
	else if (countOfTotalBaseFilesProcessed == 0) {
		throw new FileNotFoundException('No base definition files processed')
	}
	else {
		// Indicate how may files were found and processed.
		println '** INFORMATION: ' + countOfTotalBaseFiles + ' total base definition files found.'
		println '** INFORMATION: ' + countOfTotalBaseFilesProcessed + ' total base definition files processed.'

		// Output the mqsc resource definitions property variable.
		// Note: This will be used as input to the next step (the job submit step) in the UCD process.
		apTool.setOutputProperty("mqscResourceDefinitions", "${mqscResourceDefinitions}");
		apTool.storeOutputProperties();
	}

	// Trace exit and end this step with return code 0 (SUCCESS).
	if (traceEnabled) {
		println 'Exit: generate_commands.groovy'
	}

	System.exit(0)
}
// If an exception was thrown, catch it here, display a suitable error message and end this
// step with with return code 1 (FAIL).
catch (Exception e) {
	println '** ERROR: Error generating mqsc commands from input : ' + e.message + ' **'
	e.printStackTrace()
	System.exit(1)
}
