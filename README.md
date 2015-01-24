# Stash Codesearch

Stash Codesearch is a service for searching and analyzing files and commits in Atlassian Stash Git repositories. It is backed by ElasticSearch (v1.3.3).

Stash Codesearch was written by Palantir Technologies and open-sourced under the Apache 2.0 license.

## Authors

- Jerry Ma (2014, Palantir Technologies)
- Carl Myers (2014, Palantir Technologies)

## Compilation

- Install the [Atlassian Plugin SDK](https://developer.atlassian.com/display/DOCS/Set+up+the+Atlassian+Plugin+SDK+and+Build+a+Project).
- Run `atlas-package` in the repo's root directory.

## Dev/Release Workflow

This project uses versions determined by `git describe --dirty='-dirty' --abbrev=12`, and thus versions are of the form 1.2.3-N-gX where N is the number of commits since that tag and X is the exact 12-character prefix of the sha1 the version was built from.

If you build using `./build/invoke-sdk.sh`, the version will be set automatically.  Alternatively, you can set the DOMAIN_VERSION environemnt variable when invoking maven directly to override the version.

This is important because Atlassian plugins use OSGi and their version strings *must* be of the form "^\d+\.\d+\.\d+.*", so in order for jars that actually work to be produced, the tag must be a number such as "1.0.0".  For that reason, feature branches will start "features/", and be merged into "master", which will occasionally be tagged for releases.

Not every released version will necessarily be put on the Atlassian Marketplace, but every released version should be stable (i.e. pass all unit tests, and be reasonably functional).

## Installing

Before installing, you should have:

- A running Atlassian Stash instance
- A running ElasticSearch (v1.3.3) node
  - cluster name: `stash-codesearch`
  - transport listening on `localhost:9300`

You can obtain an instance of ElasticSearch by running the provided bin/install-elasticsearch-instance.sh script.

To install:

- Compile the Stash plugin (see [above](#compile-guide)).
- Upload the `target/stash-code-search-VERSION.jar` file to your Stash instance's plugin manager (`http://stash.url/plugins/servlet/upm`).

Note: you must enable global indexing and trigger a reindex after installation (see [below](#administration) for instructions).

## Testing

To test locally, you must run a local instance of ElasticSearch.  You can do this by invoking the provided bin/invoke-es.sh before running atlas-run from the Atlassian plugin SDK.

## Administration

- Go to the `Codesearch Global Settings` page in the Stash admin panel.
- Change the parameters to your desired values.
- Click `Save` to save the settings, or `Save and Reindex` to save the settings and subsequently reindex all the repositories.


## Repository Settings

By default, only master and develop are indexed. Individual repo admins may modify these settings as follows:

- Go to the `Codesearch Repository Settings` page in your repository settings panel.
- Change the ref regex to match your desired branches.
- Click `Save`.
