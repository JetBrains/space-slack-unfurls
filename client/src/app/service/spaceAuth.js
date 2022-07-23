import * as homepageData from "./homepageData";
import * as utils from "./utils";

let userToken = null;
let spaceServerUrl = null;
let spaceDomain = null;

export async function fetchSpaceUserToken() {
    let tokenResponse = await new Promise((resolve) => {
        const channel = new MessageChannel();
        channel.port1.onmessage = e => resolve(e.data);
        window.parent.postMessage({
            type: "GetUserTokenRequest",
            permissionScope: "",
            askForConsent: false
        }, "*", [channel.port2]);
    });

    if (tokenResponse != null && tokenResponse.token != null) {
        spaceServerUrl = tokenResponse.serverUrl;
        spaceDomain = new URL(spaceServerUrl).host;
        userToken = tokenResponse.token;
    }
}

export function getUserToken() {
    // TODO: make async and check for expiration
    return userToken;
}

export function approvePermissionsAndUnfurlDomains() {
    let call = async () => {
        await utils.approvePermissionsAndUnfurlDomains(homepageData.getUnapprovedPermissions(), homepageData.getUnapprovedUnfurlDomains());
    };
    call().catch(console.error);
}
