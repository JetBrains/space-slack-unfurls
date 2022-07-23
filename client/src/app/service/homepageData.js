import fetchFromServer from "./fetch.js";
import * as utils from "./utils.js";
import {runWithConfirmationFromTheUser} from "./utils.js";

let teamPresentCallback = null;
let teamListCallback = null;
let homepageData = null;

let setUnapprovedPermissions = null;
let setUnapprovedUnfurlDomains = null;

export async function fetchHomepageData() {
    let response = await fetchFromServer("/api/homepage-data");
    homepageData = (await response.json());
}

export function addSlackTeam() {
    const call = async () => {
        let response = await fetchFromServer("/api/url-for-adding-slack-team");
        utils.redirectTopWindow(await response.text());
    };
    call().catch(console.error);
}

export function removeSlackTeam(slackTeamId, teamName) {
    const call = async () => {
        await runWithConfirmationFromTheUser("Disconnect", `Would you like to disconnect the "${teamName}" Slack team?`, async () => {
            // optimistic update
            homepageData.teams = homepageData.teams.filter((team) => team.id !== slackTeamId);
            if (homepageData.teams.length === 0 && teamPresentCallback != null) {
                teamPresentCallback(false);
            }
            if (teamListCallback != null) {
                teamListCallback(homepageData.teams);
            }

            await fetchFromServer(`/api/remove-slack-team?slackTeamId=${slackTeamId}`, `POST`)
        });
    };
    call().catch(console.error);

}

export function isSlackTeamPresent() {
    return homepageData != null && homepageData.teams != null && homepageData.teams.length > 0;
}

export function getTeamList() {
    return homepageData.teams;
}

export function getUnapprovedPermissions() {
    return homepageData.unapprovedPermissions;
}

export function getUnapprovedUnfurlDomains() {
    return homepageData.unapprovedUnfurlDomains;
}

export function hasPermissionToApprove() {
    return homepageData.hasPermissionToApprove;
}

export function canManage() {
    return homepageData.canManage;
}

export function setTeamListCallback(newTeamListCallback) {
    teamListCallback = newTeamListCallback;
}

export function setTeamPresentCallback(newTeamPresentCallback) {
    teamPresentCallback = newTeamPresentCallback;
}

export function onPermissionsAndDomainsApproved() {
    homepageData.unapprovedPermissions = null;
    // homepageData.unapprovedUnfurlDomains = null;

    if (setUnapprovedPermissions != null) {
        setUnapprovedPermissions(homepageData.unapprovedPermissions);
    }
    // if (setUnapprovedUnfurlDomains != null) {
    //     setUnapprovedUnfurlDomains(homepageData.unapprovedUnfurlDomains);
    // }
}

export function setUnapprovedPermissionsCallback(newSetUnapprovedPermissions) {
    setUnapprovedPermissions = newSetUnapprovedPermissions;
}

export function setUnapprovedUnfurlDomainsCallback(newSetUnapprovedUnfurlDomains) {
    setUnapprovedUnfurlDomains = newSetUnapprovedUnfurlDomains;
}
