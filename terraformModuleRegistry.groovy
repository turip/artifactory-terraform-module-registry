
import org.artifactory.repo.RepoPath
import org.artifactory.request.Request
import org.artifactory.repo.RepoPathFactory
import org.artifactory.search.Searches
import org.artifactory.search.aql.AqlResult
import java.net.URL
import java.util.Base64
import java.nio.charset.StandardCharsets

download {
  altResponse {  Request request, RepoPath responseRepoPath ->
	status = 200
	def repo = responseRepoPath.getRepoKey()
	def path = responseRepoPath.getPath()
	if (path != "terraform-registry") {
		return
	}
	def command = request.getHeader('X-Artifactory-Terraform-Command')
	def module = request.getHeader('X-Artifactory-Terraform-Module')
	def provider = request.getHeader('X-Artifactory-Terraform-Provider')
	def entity = request.getHeader('X-Artifactory-Terraform-Entity-ID')

	if (command == null) {
		log.warn("No command name is set in the request")
		message = "no command set"
		status = 400
	} else if (command == "versions") {
		if ((module == null) || (provider == null)) {
			log.warn("No module name or provider is set")
			message = "Please specify the module name or provider"
			status = 400
			return
		}
		message = collectVersionsFor(repo, module, provider)
	} else if (command == "download") {
		def downloadBaseUrl = calculateBaseUrl(request)
		if ((module == null) || (provider == null) || (entity == null)) {
			log.warn("No module name, provider or entity is set")
			message = "Please specify the module name, provider and entity-id" + groovy.json.JsonOutput.toJson(request.getHeaders())
			status = 400
			return
		}
		try {
			download_url = getDownloadUrl(downloadBaseUrl, repo, module, provider, entity)
			headers['X-Terraform-Get'] = download_url
			status = 204
			message = ""
		} catch (Exception e) {
			message = e.toString()
			status = 400
		}
	} else {
		message = "Invalid command: " + command
		status = 400
	}
  }
}

def calculateBaseUrl(request) {
	// Please help me coming up with longer header names :D
	def override_base_url = request.getHeader("X-Artifactory-Terraform-Download-Base")
	// First we need the authorization header to have the same credentials as the current caller
	def auth_header = request.getHeader('authorization')
	def anonymous = false
	def user_secret
	if (auth_header == null) {
		// Note: this code path was never tested, as you can just upload your stuff to registry.terraform.io
		anonymous = true
	} else {
		log.info("Auth hreader= " + auth_header)
		def (kind, secret) = auth_header.split(' ')
		log.info("Auth hreader= " + kind + "/" + secret)
		user_secret = new String(Base64.getDecoder().decode(secret), StandardCharsets.UTF_8)
	}
	if (override_base_url == null) {
		retval = request.getServletContextUrl() 	
	} else {
		retval = override_base_url
	}

	if (!anonymous) {
		def url = new URL(retval)
		retval = url.getProtocol() + "://" + user_secret + "@" + url.getAuthority() + url.getPath()
	}

	if (retval.endsWith("/")) {
		return retval[0..-2]
	}
	return retval

}

def findVersionsFor(String repo, String module, String provider) {
	def result_set = []
	def search = [
		repo: repo,
		'$and': [ [ '@terraform.module.name': [ '$eq': module ] ],
		          [ '@terraform.module.provider': [ '$eq': provider ] ]]]
	((Searches) searches).aql("items.find(" + groovy.json.JsonOutput.toJson(search) + ").include(\"*\",\"property.*\")") {
		AqlResult result ->
               		result.each {
				def propertyMap = [:]
				it.properties.each {
					propertyMap[it.key] = it.value.toString()
				}
				// Let's assemble the version data based on the properties and the output
				def version_data = [
					version: propertyMap['terraform.module.version'],
					root: [ 
						providers: [[ // [[ == arrays of hash, how much I love groovy
							name: propertyMap['terraform.module.provider'],
							version: ""	// TODO: If we want to have this setting
						]],
						dependencies: [], // TODO
					],
					submodules: [] // TODO
					]
				result_set << version_data
               		}
	}

	return result_set
}

def collectVersionsFor(String repo, String module, String provider) {

	def response = [:]
	response['modules'] = [[
		source: repo + "/" + module + "/" + provider,
		versions: findVersionsFor(repo, module, provider)]]

	return groovy.json.JsonOutput.toJson(response)
}

def getDownloadUrl(String baseUrl, String repo, String module, String provider, String version) {
	def result_set = []
	def search = [
		repo: repo,
		'$and': [ [ '@terraform.module.name': [ '$eq': module ] ],
			  [ '@terraform.module.provider': [ '$eq': provider] ],
		          [ '@terraform.module.version': [ '$eq': version ] ]]]
	((Searches) searches).aql("items.find(" + groovy.json.JsonOutput.toJson(search) + ")") {
		AqlResult result ->
               		result.each {
				result_set << it.repo + "/" + it.path + "/" + it.name
               		}
	}

	if (result_set.size() == 0) {
		throw new Exception("Cannot find terraform module: " + repo + "/" + module + "/" + provider + "/" + version)
	} else if (result_set.size() == 1) {
		return baseUrl + "/" + result_set[0]
	} else {
		throw new Exception("Multiple terraform modules found for: " + repo + "/" + module + "/" + provider + "/" + version)
	}
}
