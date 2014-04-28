# Stash Codesearch

Stash Codesearch is a service for searching and analyzing files and commits in Atlassian Stash Git repositories. It is backed by ElasticSearch (v1.1).


## Authors

- Jerry Ma (2014, Palantir Technologies)


## Compilation

- Install the [Atlassian Plugin SDK](https://developer.atlassian.com/display/DOCS/Set+up+the+Atlassian+Plugin+SDK+and+Build+a+Project).
- Run `atlas-package` in the repo's root directory.


## Installing

Before installing, you should have:

- A running Atlassian Stash instance
- A running ElasticSearch (v1.1) node
  - cluster name: `stash-codesearch`
  - transport listening on `localhost:9300`

To install:

- Compile the Stash plugin (see [above](#compile-guide)).
- Upload the `target/stash-code-search-VERSION.jar` file to your Stash instance's plugin manager (`http:/stash.url/plugins/servlet/upm`).

Note: you must enable indexing and trigger a reindex after installation (see [below](#administration) for instructions).


## Administration

- Go to the `Codesearch Global Settings` page in the Stash admin panel.
- Change the parameters to your desired values.
- Click `Save` to save the settings, or `Save and Reindex` to save the settings and subsequently reindex all the repositories.


## Repository Settings

By default, only master and develop are indexed. Individual repo admins may modify these settings as follows:

- Go to the `Codesearch Repository Settings` page in your repository settings panel.
- Change the ref regex to match your desired branches.
- Click `Save`.
