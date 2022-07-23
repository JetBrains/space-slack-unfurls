import * as homepageData from "./homepageData";

export function redirectTopWindow(redirectUrl) {
    const channel = new MessageChannel();
    window.parent.postMessage({
        type: "RedirectWithConfirmationRequest",
        redirectUrl: redirectUrl
    }, "*", [channel.port2]);
}

export async function approvePermissionsAndUnfurlDomains(permissions, unfurlDomains) {
    let response = await new Promise((resolve) => {
        const channel = new MessageChannel();
        channel.port1.onmessage = e => resolve(e.data);
        window.parent.postMessage({
            type: "ApprovePermissionsRequest",
            permissionScope: permissions,
            unfurlDomains: unfurlDomains, // doesn't work for now, but should with the next deployment of Space
            purpose: ""
        }, "*", [channel.port2]);
    });

    if (response.success) {
        homepageData.onPermissionsAndDomainsApproved()
    }
}

export async function runWithConfirmationFromTheUser(okButtonText, question, block) {
    let response = await new Promise((resolve) => {
        const channel = new MessageChannel();
        channel.port1.onmessage = e => resolve(e.data);
        window.parent.postMessage({type: "ShowConfirmDialogRequest", okButtonText: okButtonText, question: question}, "*", [channel.port2]);
    });

    if (response) {
        block();
    }
}
