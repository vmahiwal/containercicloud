/**
 * Version: 2
 *
 * ChangeLog:
 *  - Added function executeWithMaven(command)
 */


/**
 *  Usage:
 *  def talend_ci
 *  configFileProvider([
 * 		configFile(fileId:'jenkins_talend_helper',  targetLocation: 'jenkins_talend_helper.groovy')] ) {
 *  	talend_ci = load 'jenkins_talend_helper.groovy'
 *  }
 *  talend_ci.checkParameters(printToConsole: true,updateBuildDescription: true)
 */


/**
 * checkParameters (printToConsole, updateBuildDescription)
 * printToConsole -> prints the parameter name + value to the console
 * updateBuildDescription -> appends the parameter name + value to the build description
 */
def checkParameters(printToConsole, updateBuildDescription) {
    //TODO
	def envParamList = "CI_BUILDER_VERSION,PROJECT_TO_BUILD,JOBS_TO_BUILD,"+
	"GIT_PROJECT_LIST,GIT_CREDENTIALS_ID,"+
	"TALEND_CI_INSTALL_CONFIG,TALEND_CI_RUN_CONFIG,"+
	"TALEND_CI_BUILD_OPTIONS,"+
	"MVN_GOALS,MVN_EXTRA_PARAMS,M2_HOME"

	def descriptiontext = ""  
	for(param in envParamList.split(",") ) {
		descriptiontext += '\n' + param + ": " + env."${param}"
	}
	descriptiontext += '\n' +'mavenToUse: ' + mavenToUse
	descriptiontext += '\n' +'maven_settings: ' + maven_settings
	if(updateBuildDescription) {
		currentBuild.description = descriptiontext
	}
	if(printToConsole) {
		println(descriptiontext)
	}
  
  	//Name: UNIX_TOOLS
	//Description: Only when job runs on windows. If set to true then instead of windows specific commands, we'll use Linux commands. 
	//         This makes the script to use cp instead of xcopy (Xcopy can throw Insufficient Memory error when the path length is greater than 255 characters)
	//Type: String
	env.UNIX_TOOLS=false
}

/**
 * checkoutAllProjects(gitProjectList)
 * gitProjectList should be a multiline string in the following format:
 * PROJECT_NAME;GIT_URL;GIT_BRANCH
 * Can accept one or multiple projects
 */
def checkoutAllProjects(gitProjectList){
	for(project in gitProjectList.split("\n") ) {
		checkoutProject(project.split(";")[0], project.split(";")[1], env.GIT_CREDENTIALS_ID, project.split(";")[2])
	}
}

/**
 * checkoutProject(projectName, gitURL, gitCredentailsID, gitBranch)
 * projectName the project to check out from the GIT repository.  
 * gitURL remote repository location
 * gitCredentailsID the ID configured under jenkins credentials
 * gitBranch remote branch
 */
def checkoutProject(projectName, gitURL, gitCredentailsID, gitBranch){
	println("Checkout project - " + projectName +" begin \n"+
		"Git URL: " + gitURL +"\n"+
		"Git branch: " + gitBranch)
	try {
		println("Remove project Folder ./"+projectName)
		dir("./"+projectName) {
			deleteDir()
		}
	} catch (err) {
		println("Failed to remove project Folder: ./"+projectName)
		println(err)
	}
	
    dir('.repositories/'+ projectName) {
        git(
            url: gitURL,
            credentialsId: gitCredentailsID,
            branch: gitBranch
        )  
		if(isUnix()){
			sh "cp -R "+projectName+" ../../"+projectName
        } else {
			if(unixTools()){
				bat "cp -R "+projectName+" ../../"+projectName
			} else {
				bat "xcopy "+projectName+" ..\\..\\"+projectName+"\\ /E /H /Y"
			}
		}
    }
	println("Checkout project - " + projectName +" done")
}

/**
 * displayAvailableJobsToBuild(projectName)
 * Parses the projectName/poms/pom.xml file and lists the available <modules>
 * JOBS_TO_BUILD parameter excepts these modules.
 */
def displayAvailableJobsToBuild(projectName) {
	println("displayAvailableJobsToBuild - begin - Project: " + projectName)

	try {
		def pom = readFile("./"+ projectName + "/poms/pom.xml")
		def modulesFound = false;
      	def toPrint = "";
		for(line in pom.split("\n") ) {
			if(line.contains("modules>") ) {
				modulesFound = !modulesFound;
			} else if(modulesFound) {
				toPrint += line.trim().replace("<module>","").replace("</module>","") +"\n";
			}
		}
      	println(toPrint);
	} catch (err) {
		println("displayAvailableJobsToBuild - Can't parse POM file: " + projectName+ "/poms/pom.xml")
		println(err)
	}
	println("displayAvailableJobsToBuild - done - Project: " + projectName)
}

/**
 * generateAllPoms()
 * Triggers org.talend.ci:builder-maven-plugin to generate POMS.
 * This will install CI based on: 
 * 		env.TALEND_CI_INSTALL_CONFIG
 * 		env.TALEND_CI_RUN_CONFIG
 */
def generateAllPoms() {
	println("generateAllPoms - begin")
	withMaven(
		maven: mavenToUse,
		mavenSettingsConfig: maven_settings,
      	mavenLocalRepo: env.M2_HOME,
        mavenOpts: 
			env.TALEND_CI_INSTALL_CONFIG + " " +
            env.TALEND_CI_RUN_CONFIG
    ) {
        runCommand("echo $M2_HOME")
        runCommand("mvn --batch-mode org.talend.ci:builder-maven-plugin:${CI_BUILDER_VERSION}:generateAllPoms" +" "+env.MVN_EXTRA_PARAMS)
    }
	println("generateAllPoms - done")
}

/**
 * runCommand(command)
 * executes a shell/batch command
 */
def runCommand(command){
	if(isUnix() ) {
		sh command.replaceAll("\\r?\\n"," ")
    } else {
		bat command.replaceAll("\\r?\\n"," ")
    }
}
/**
 * buildJobs()
 * Triggers maven to build jobs
 * This will use 
 *   env.TALEND_CI_RUN_CONFIG
 *   env.PROJECT_TO_BUILD
 *   env.JOBS_TO_BUILD
 *   env.MVN_GOALS
 */
def buildJobs() {
	println("buildJobs - begin")
	withMaven(
		maven: mavenToUse,
		mavenSettingsConfig: maven_settings,
      	mavenLocalRepo: env.M2_HOME,
        mavenOpts: 
            env.TALEND_CI_RUN_CONFIG
    ) {
		def command = "mvn --batch-mode "+
		"-f ./" + env.PROJECT_TO_BUILD+"/poms/pom.xml "+ 
		env.MVN_GOALS+
		" -fae -am -e "+
		" -pl "+env.JOBS_TO_BUILD+
        " "+env.TALEND_CI_BUILD_OPTIONS+
        " "+env.MVN_EXTRA_PARAMS
		runCommand(command)
    }
	println("buildJobs - done")
}

/**
 * This function checks if Unix Tools are available in the windows path
*/
def unixTools(){
	if((""+env.UNIX_TOOLS).equals("true")) {
		println("UNIX_TOOLS are enabled - env.UNIX_TOOLS="+ env.UNIX_TOOLS)
		return true;
	}
//	println("UNIX_TOOLS are disabled - env.UNIX_TOOLS="+ env.UNIX_TOOLS)
	return false;
}


/**
 * setMvnVersion(version, snapshot)
 * Calls Studio in scripted mode through Maven to change the Version of the project
 * Parameters:
 * version the version to be set
 * snapshot true/false
 */
def setMvnVersion(version, snapshot) {
	if(env.releaseAs.equals("none") ) {
		println("setMvnVersion -  skipped")
		return;
	}
	println("setMvnVersion -  begin")
	
	//preparing the script to be executed:
	def command = "logonProject -pn "+env.PROJECT_TO_BUILD+" -ul 'jobbuilder@talend.com' -gt"
	command += "\nchangeMavenVersion " + version
	if(snapshot) {
		command += " --snapshot"
	}
	def fileName = 'changeMavenVersion.script'
	writeFile(file: fileName ,text: command)
	executeScript(fileName)
	println("setMvnVersion -  done")
}

/**
 * executeScript(scriptFile)
 * Calls Studio in scripted mode through Maven to run commands specified in files
 * Parameters:
 * scriptFile to be executed
 */
def executeScript(scriptFile) {
    withMaven(
        maven: mavenToUse,
        mavenSettingsConfig: maven_settings,
      	mavenLocalRepo: env.M2_HOME,
        mavenOpts: env.TALEND_CI_RUN_CONFIG
		) {
    		runCommand("mvn org.talend.ci:builder-maven-plugin:${CI_BUILDER_VERSION}:executeScript -DscriptFile=./"+scriptFile+" "+env.MVN_EXTRA_PARAMS)
    }
}

/**
 *  getNextVersion(version, increment)
 * 	Calculates the Next version based on the increment.
 *  If version is: 1.2.3
 *  Increment will have the following effect:
 *  patch -> 1.2.4
 *  minor -> 1.3.0
 *  major -> 2.0.0
 *  none  -> 1.2.3
 */  
def getNextVersion(version, increment) {
    pattern="([0-9]+)\\.([0-9]+)\\.([0-9]+)"
    int versionMajor=version.replaceAll(pattern,"\$1")
    int versionMinor=version.replaceAll(pattern,"\$2")
    int versionPatch=version.replaceAll(pattern,"\$3")
    if(increment.equalsIgnoreCase("major")) {
        versionMajor++
        versionMinor = 0
        versionPatch = 0
    } else if(increment.equalsIgnoreCase("minor")) {
        versionMinor++
        versionPatch = 0
    } else if(increment.equalsIgnoreCase("patch")) {
        versionPatch++
    } else if(increment.equalsIgnoreCase("none")) {
        //do nothing
    } else {
            throw new Exception ("Invalid value for increment, accepted values are: [major,minor,patch,none] but got " + increment)
    }
    
    newVersion=versionMajor+"."+versionMinor+"."+versionPatch;
    println("getNextVersion - increment "+increment+" " + version + " -> " + newVersion)
    return newVersion
}

/**
 * executeWithMaven(command)
 * Triggers command with maven, e.g: install:install-file
 * This will install CI based on: 
 * env.TALEND_CI_INSTALL_CONFIG
 * env.TALEND_CI_RUN_CONFIG
 */
def executeWithMaven(command) {
   println("executeWithMaven - begin")
   withMaven(
     maven: mavenToUse,
     mavenSettingsConfig: maven_settings,
     mavenLocalRepo: env.M2_HOME,
     mavenOpts: 
       env.TALEND_CI_INSTALL_CONFIG + " " +
       env.TALEND_CI_RUN_CONFIG
      ) {
       runCommand("mvn --batch-mode "+ command +" "+env.MVN_EXTRA_PARAMS)
      }
   println("executeWithMaven - done")
}

return this