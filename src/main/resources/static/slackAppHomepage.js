let addSlackTeamBtn = document.getElementById("add-slack-team-btn");
let slackTeamsBlock = document.getElementById("slack-teams-block");
let slackTeamsList = document.getElementById("slack-teams-list");
let slackTeamsListHeader = document.getElementById("slack-teams-list-header");

window.onload = async () => {

    // subscribe to changes in theme css variables
    window.addEventListener("message", (e) => {
        if (e.data.cssVars !== undefined) {
            applyCssVars(e.data.cssVars);
        }
    });
    const themeCssVars = await getCssVarsAndSubscribeForChanges();
    applyCssVars(themeCssVars.properties);

    // load Slack teams connected to current Space organization
    await refreshSlackWorkspacesList()

    // connecting Slack workspace to Space org
    addSlackTeamBtn.addEventListener("click", onAddSlackTeamBtnClick)
}

function applyCssVars(cssVars) {
    cssVars.forEach((cssVar, i) => {
        document.documentElement.style.setProperty(cssVar.name, cssVar.value);
    })
}

function getCssVarsAndSubscribeForChanges() {
    return new Promise((resolve) => {
        const channel = new MessageChannel();
        channel.port1.onmessage = e => resolve(e.data);
        window.parent.postMessage({type:"GetThemePropertiesRequest", subscribeForUpdates: true}, "*", [channel.port2]);
    });
}

function getUserAccessTokenData() {
    return new Promise((resolve) => {
        const channel = new MessageChannel();
        channel.port1.onmessage = e => resolve(e.data);
        window.parent.postMessage({type:"GetUserTokenRequest", permissionScope:"", askForConsent: false}, "*", [channel.port2]);
    });
}

function confirmDialog(options) {
    return new Promise((resolve) => {
        const channel = new MessageChannel();
        channel.port1.onmessage = e => resolve(e.data);
        window.parent.postMessage({...options, type: "ShowConfirmDialogRequest"}, "*", [channel.port2]);
    });
}

async function onAddSlackTeamBtnClick() {
    let {token, serverUrl} = await getUserAccessTokenData();
    let response = await fetch(
        `/add-slack-team?backUrl=${serverUrl}`,
        { method: "POST", headers: { "Authorization": "Bearer " + token } }
    );
    window.top.location = await response.text();
}

function showTeamsListEmptyState() {
    let emptyState = document.createElement("li");
    emptyState.classList.add("empty-state");
    emptyState.innerHTML = "No Slack teams connected";
    slackTeamsList.appendChild(emptyState);
    slackTeamsListHeader.classList.add("hidden");
}

async function refreshSlackWorkspacesList() {
    let {token} = await getUserAccessTokenData();
    let response = await fetch("/slack-teams", {headers: {"Authorization": "Bearer " + token}});
    let {teams, canManage} = await response.json();
    slackTeamsList.innerHTML = "";

    if (teams.length > 0) {
        for (let team of teams) {
            let fullSlackDomain = team.domain + ".slack.com";
            let listItem = document.createElement("li");
            let div = document.createElement("div");
            div.classList.add("slack-teams-list-item");
            div.innerHTML = `<a href="https://${fullSlackDomain}">${fullSlackDomain}</a>`;
            if (canManage) {
                let removeBtn = document.createElement("a");
                removeBtn.classList.add("remove-slack-team-btn");
                removeBtn.innerText = "Disconnect";
                removeBtn.addEventListener("click", () => { onRemoveSlackTeamBtnClick(team.id, team.domain, removeBtn, listItem) });
                div.appendChild(removeBtn);
            }
            listItem.appendChild(div);
            slackTeamsList.appendChild(listItem);
        }
        slackTeamsListHeader.classList.remove("hidden");
    } else {
        showTeamsListEmptyState();
    }

    if (canManage)
        addSlackTeamBtn.classList.remove("hidden")
    else
        addSlackTeamBtn.classList.add("hidden")

    slackTeamsBlock.classList.remove("hidden");
}

async function onRemoveSlackTeamBtnClick(teamId, teamDomain, removeBtn, listItem) {
    let confirmationOptions = {
        question: "Disconnect Slack team",
        okButtonKind: "DANGER",
        okButtonText: "Disconnect",
        description: `Disconnect ${teamDomain}.slack.com from your Space organization?`
    };
    if (await confirmDialog(confirmationOptions)) {
        let spinner = document.createElement("div");
        spinner.classList.add("spinner");
        removeBtn.parentElement.appendChild(spinner);
        removeBtn.parentElement.removeChild(removeBtn);

        let spaceUserAccessToken = await getUserAccessTokenData();
        let response = await fetch(
            `/remove-slack-team?slackTeamId=${teamId}`,
            {method: "POST", headers: {"Authorization": "Bearer " + spaceUserAccessToken}}
        );
        if (response.ok) {
            slackTeamsList.removeChild(listItem);
            if (slackTeamsList.children.length === 0) {
                showTeamsListEmptyState()
            }
        }
    }
}
