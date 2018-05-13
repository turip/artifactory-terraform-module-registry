# artifactory-terraform-module-registry
Artifactory *PRO* plugin for hosting private terraform module registries. 

You can install and use this source to have a private terraform module regisitry
based on artifactory. Please note that whenever Artifactory would officially
support terraform module registries, that solution will be superior to this one,
so please use that.

Also please note that you *need* to have at least Artifactory PRO to use this
plugin as:
- It uses AQL for metadata searches
- I prefer to not to have terraform registry as a non-ha setup (and only
  terraform pro provides replication)

Also it is mandatory to have a http server in front of your artifactory instance
due to artifactory plugins being quite limited.

# Installing

In order to install the plugin you need to first copy the
``terraformModuleRegistry.groovy`` file from the repository into
/etc/opt/jfrog/artifactory/plugins/ (ubuntu).

Artifactory should pick up the plugin, but it's always safer to restart or to
reload the plugins using

    curl -X POST http://localhost:8081/artifactory/api/plugins/reload -u<artifactory-user>:<artifactory-password>

You will also need to add the following to your apache2 configuration:

    RewriteEngine on
    # For requests like terraform-registry/v1/modules/example-repo-local/data/aws/0.2.3/download
    RewriteRule ^/terraform-registry/v1/modules/([^/]+)/([^/]+)/([^/]+)/([^/]+)/([^/]+)$ /artifactory/$1/terraform-registry [E=TF_MODULE_NAME:$2,E=TF_MODULE_PROVIDER:$3,E=TF_MODULE_VERSION:$4,E=TF_MODULE_COMMAND:$5,L,PT,E=TF_USE_BASIC_AUTH:1]
    # For requests like terraform-registry/v1/modules/example-repo-local/data/aws/versions
    RewriteRule ^/terraform-registry/v1/modules/([^/]+)/([^/]+)/([^/]+)/([^/]+)$ /artifactory/$1/terraform-registry [E=TF_MODULE_NAME:$2,E=TF_MODULE_PROVIDER:$3,E=TF_MODULE_COMMAND:$4,L,PT,E=TF_USE_BASIC_AUTH:1]
    # Make sure that Bearer -> Basic conversion happens for downloads too
    RewriteRule ^/terraform-registry/download/(.*) /artifactory/$1 [L,PT,E=TF_USE_BASIC_AUTH:1]
    RequestHeader set X-Artifactory-Terraform-Module %{TF_MODULE_NAME}e env=TF_MODULE_NAME
    RequestHeader set X-Artifactory-Terraform-Provider %{TF_MODULE_PROVIDER}e env=TF_MODULE_PROVIDER
    RequestHeader set X-Artifactory-Terraform-Entity-ID %{TF_MODULE_VERSION}e env=TF_MODULE_VERSION
    RequestHeader set X-Artifactory-Terraform-Command %{TF_MODULE_COMMAND}e env=TF_MODULE_COMMAND
    RequestHeader set X-Artifactory-Terraform-Download-Base https://<your-hostname>/terraform-registry/download/ env=TF_USE_BASIC_AUTH
    
    # TODO: Using https://stackoverflow.com/questions/21032461/how-to-base64-encode-apache-header we might be able to implement base64 here
    # Change Bearer tokens into Basic auth for terraform access
    RequestHeader edit Authorization ^Bearer Basic env=TF_USE_BASIC_AUTH

Also you need to serve the ```.well-known/terraform.json``` file from your server
using the following contents:

    {"modules.v1":"/terraform-registry/v1/modules/"}

The eaiest way on an ubuntu server is to just create the file
```/var/www/html/.well-known/terraform.json``` file with these contents.

# Using the plugin

The plugin only works on local repositories and does not care what kind of
repository you had created. For testing I was using generic repositories.

In order to enable terraform support for a given repository please deploy any
file named terraform-registry in the root of the folder. (Contents will be
dynamically generated by the plugin)

After this if you upload any tar.gz into the repository with the following
properties set:

* terraform.module.name: name of the module
* terraform.module.provider: provider this module is specific to
* terraform.module.version: version of the module (this must be uniq per name
  and provider)

After this you can reference the module from terraform using such references:

    module "whatever" {
        source = "<your-url>/<your-local-repo>/data/aws"
        version = ">= 0.1"
    }

Where data is the name of your module and aws is the provider.

In order to have authentication working you also need to create a new
.terraformrc file in your home directory with the following contents:

    credentials "<hostname>" {
           token = "<base64 of your username:api_token>"
    }

The hostname is the hostname without protocol name (e.g.
artifactory.example.org), the username is your artifactory user name and the
api_token is the API Token obtained on the web interface.

# Uploading modules

In order to upload a locally available module into the given repository first
you need to compress it. Assuming that you have your module in folder called
my-plugin you can use this command:

    tar -czvf my-plugin.tar.gz -C my-plugin .

After this is done you can use the following command to upload the artifact into
the given repository:

    curl -u ${ARTIFACTORY_USER}:${ARTIFACTORY_PASSWORD} \
         --upload-file my-plugin.tar.gz \
         'https://<your-server>/artifactory/<repo>/my-plugin-aws-0.3.4.tar.gz;terraform.module.name=my-pluigin;terraform.module.version=0.3.4;terraform.module.provider=aws'

Please set the terraform.module.* according to your release practices. Please
not that you can upload any number of plugins into the same repository with the
same name, version and provider under different filenames. In such cases
download will fail as as the plugin will refuse to give you a download link. The
reason for this is that if you have multiple code-versions of the same module you
must create different versions from the modules too.

# How this works?

## Version query

The plugin provides a hook, that activates if the terraform-registry is present
in a repository. Apache is used to rewrite the
/terraform-registry/v1/modules url prefix and transform the path components
into HTTP headers.

The first entry point that the plugin implements is for the likes of:

    terraform-registry/v1/modules/example-repo-local/data/aws/versions

The system uses AQL to search for the properties mentioned in the Using part and
then returns the list of available versions in the terraform expected ways.

## Authentication

Notice: terraform uses Bearer authentication type to authenticate to the
registry. There is a hack inside the apache rewrite rules to make sure that
converts whatever inside the Bearer token into a Basic authentication header.

The reason for this is that when the download happens terraform only accepts an
URL for the download, for me this suggests that in their case the download is
not authenticated (as I don't know if a Bearer token) can be transferred using
an URL. (I suppose they are using pseudo random hashes to prevent leaks).

Artifactory uses Bearer authentication for authorization tokens, that have a
fixed lifespan and also quite hard to manage without directly accessing the API.

Based on the two above the implementation is only capable of using Basic
authentication. This means that you need to generate an API key on the
artifactory UI and use that in your terraform.rc file.

## Download url query

The second function returns the URL that terraform should be downloading the
module from. Please note that the version compare logic is implemented at
terrafrom side.

These urls look like this:

    terraform-registry/v1/modules/example-repo-local/data/aws/0.2.3/download

The registry must return an empty content and the header of X-Terraform-Get
containing the URL for the binaries. The module parses the authentication header
in your request and passes back that as part of this variable, so that the
whole download process is authenticated.

# FAQ

## Why this apache madness?

The main reason is that the artifactory plugin api is limited, and I need to
"emulate" a REST API on top.

Also please see the part about the Basic/Bearer authentication issue.

The conclusion is that it will not get better until JFrog properly includes this
into their Artifactory product.

## Can I use this using Artifactoy OSS?

No. If you don't want to pay for neither Artifactory PRO or Terraform
Enterprise, you should just create your own implementation and opensource it.

## Why using Artifactory PRO instead of a standalone solution?

I could have, but we have a properly functioning Artifactory at prezi, that has
authentication and SOC2 compliance sorted, so I would prefer to not to have
those reimplemented.
