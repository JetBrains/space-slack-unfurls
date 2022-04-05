# slack-unfurls

This is the application for providing link previews between Slack and Space in both directions. It provides link previews 
for Slack message links in Space and link previews for Space issue, code review and chat message links in Slack.
The root page of the application provides a simple UI for installing it to a Space organization or a Slack workspace.
This application is multi-tenant on both ends - one instance of the application can serve multiple Space organizations
and multiple Slack workspaces.

The content for link previews is fetched on behalf of the user posting a message. So, Slack users are invited to authenticate
themselves in Space when posting a message with a link to this Space organization for the first time.
Similarly, when Space users first posts a message with a link to some Slack workspace, they are invited to authorize themselves
in that workspace. The invitation can be dismissed once or permanently.

Application storage layer is hidden behind the interface with PostgreSQL and DynamoDB implementations. The storage to be used
is determined by the environment variables referenced in `application.conf`, with DynamoDB taking precedence.
Database is used to store the domains of the connected Space organizations and Slack workspaces, their client credentials and
user tokens obtained via OAuth flow to fetch link preview content on users' behalf. As an additional security layer,
all the secrets (app and user tokens) are stored in database in an encrypted form, using the encryption key 
from `ENCRYPTION_KEY` environment variable.

Most of the application logic is contained in either `slackUnfurlsInSpace` or `spaceUnfurlsInSlack` folders that take care 
of providing link previews in corresponding direction. The `SpaceOAuthFlow.kt`/`SlackOAuthFlow.kt` files are responsible for 
the corresponding user OAuth flow - Space users needs to authenticate in Slack in order to get Slack link previews 
for their Space messages, and vice versa.

## Application setup in Space

No special beforehand setup is needed on Space side. Just open the default page of the running application, enter the Space organization
domain in the text input and press `Add to Space` button to install the application. After the installation, be sure to go to application settings
and approve requested permissions on `Authorization` tab as well as requested unfurl domain (slack.com) on `Unfurls` tab.

## Application setup in Slack

Slack application has to be created manually before distributing it to different Slack workspaces. Visit https://api.slack.com/apps 
and create a new app there by pressing the `Create New App` button. 

The application in Slack can be configured either via the manifest or manually. Here is what the manifest in yaml format should look like for the application: 

```yaml
display_information:
  name: JetBrains Space Link Previews
  description: Provides link previews for Space in Slack and vice versa
  background_color: "#1a181a"
features:
  bot_user:
    display_name: JetBrains Space Link Previews
    always_online: false
  unfurl_domains:
    - jetbrains.space
oauth_config:
  redirect_urls:
    - https://<app-host>/slack/oauth/callback
  scopes:
    bot:
      - links:read
      - links:write
      - team:read
settings:
  event_subscriptions:
    request_url: https://<app-host>/slack/events
    bot_events:
      - link_shared
      - team_domain_change
      - app_uninstalled
  interactivity:
    is_enabled: true
    request_url: https://<app-host>/slack/interactive
  org_deploy_enabled: false
  socket_mode_enabled: false
  token_rotation_enabled: true
```

Alternatively, you can configure the following settings on the application configuration page:
* On `Interactivity & Shortcuts` page, enable interactivity and specify request url as `https://<app-host>/slack/interactive`.
  This is needed to respond to clicking the buttons in authentication requests;
* On `Event Subscriptions` page:
  * enable events and specify request url as `https://<app-host>/slack/events`.
    This is the endpoint Slack will send notifications to when Space links are encountered in user's message.
  * subscribe to the bot events `link_shared`, `team_domain_change` and `app_uninstalled`.
  * add Space domains you want to handle to `App Unfurl Domains` section. Keep in mind that Slack will also handle subdomains of these domains, so a single `jetbrains.space` entry covers all the cloud Space orgs.
* On `OAuth & Permissions` page:
  * specify the redirect url as `https://<app-host>/slack/oauth/callback`.
    This is part of OAuth flow for the Space users authenticating in Slack, and also of the application installation flow for a given workspace.
  * opt into advanced token security via token rotation.
  * add `links:read`, `links:write` and `team:read` scopes to the list of Bot token scopes.
    These are permission scopes that will be requested upon installing the application to a Slack workspace.
* On the `Manage Distribution` page you'll now be able to activate public distribution of your Slack application.
  This doesn't mean it's going to be published to a Marketplace - it's only enabling installing the application to a different Slack workspace
  by the means of OAuth flow (which is initiated by the `Add to Slack` button on the application default page). 
  
Note that it is necessary to enable public distribution and install the application to a Slack workspace via the `Add to Slack` button from the hosted app's default page
even if the only Slack workspace you're going to work with is the same Slack workspace that you initially specified when creating the application in Slack.
The reason is that upon app installation flow completion, the hosted application will receive a callback from Slack and will store
the Slack workspace domain along with its app-level token in the database. Without this step, the Slack workspace domain won't be considered
connected to any Space instance for the purpose of providing link previews.

## Running the application locally

Use Gradle `run` task for running the application locally. It will spin up a docker container with the local DynamoDB instance and
configure all the credentials to connect to it. Some additional setup is still needed:

* First, make sure you have Docker compose installed locally - it is needed for running the local DynamoDB container;
* Set up a Slack application as described in [Application setup in Slack](#application-setup-in-slack) (or use an existing one);
* Run ngrok to set up a tunnel to the local application instance (needed for Slack and Space to be able to call into the application's API);
* Go to your application settings in Slack and modify all the urls there to point to ngrok tunnel url.
  The most convenient way to do this is via `App Manifest` tab (https://app.slack.com/app-settings/<team-id>/<app-id>/app-manifest) because 
  all the urls are there in one place. You need to fix three urls - redirect url for OAuth, request url for event subscriptions and request url for interactivity.
* Copy `template_local.properties` file in the project root into the `local.properties` file (this isn't intended to be committed to git, so it's included in gitignore);
* Edit the `local.properties` file to provide proper configuration values: 
  * Specify ngrok tunnel https endpoint for the `app.entrypointUrl` url;
  * Specify the key for encrypting secrets that are stored in database. A simple way to do this is to generate the key 
    https://www.allkeysgenerator.com/Random/Security-Encryption-Key-Generator.aspx (`Encryption key` tab, `256-bit` option).
  * Specify client id, client secret and signing secret for the Slack application created. They are generated by Slack when creating the application
    and can be found in the `Basic information` -> `App Credentials` section of the application page (https://api.slack.com/apps/<app-id>/general).
* And finally start the application by `./gradlew run`, visit its default page (https://<your-ngrok-host>), 
  install app to at least one Slack workspace and Space organization and see the link previews in action. 
    
Remember to reconfigure the Slack application settings and then reinstall the application to both Slack workspace and Space organization 
whenever the ngrok tunnel is reestablished with a new address. Space allows multiple applications with the same name, so it's better to drop
the previous installations from the test organization before installing the application afresh.
