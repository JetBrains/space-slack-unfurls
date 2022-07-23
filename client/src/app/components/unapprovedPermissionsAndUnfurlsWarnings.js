import {useState} from "react";
import * as homepageData from "../service/homepageData";
import WarningBox from "./warningBox";
import * as spaceAuth from "../service/spaceAuth";

// TODO: make a single warning after the next Space deployment
//       when an administrator will be able to approve permissions
//       and unfurl domains both in the same dialog
export default function UnapprovedPermissionsAndUnfurlsWarnings() {
    let [unapprovedPermissions, setUnapprovedPermissions] = useState(homepageData.getUnapprovedPermissions());
    homepageData.setUnapprovedPermissionsCallback(setUnapprovedPermissions);

    let [unapprovedUnfurlDomain, setUnapprovedUnfurlDomains] = useState(homepageData.getUnapprovedUnfurlDomains());
    homepageData.setUnapprovedUnfurlDomainsCallback(setUnapprovedUnfurlDomains);

    let hasPermissionToApprove = homepageData.hasPermissionToApprove()

    if (unapprovedPermissions != null) {
        let warningText = hasPermissionToApprove ? "Approve permissions for the application" : "Space administrator needs to approve permissions for the application.";
        return (
            <WarningBox isActionable={hasPermissionToApprove}
                        text={warningText}
                        onAction={() => spaceAuth.approvePermissionsAndUnfurlDomains()}
                        style={{alignSelf: 'stretch'}}
            />
        );
    } else if (unapprovedUnfurlDomain != null) {
        let warningText = hasPermissionToApprove ? "Approve unfurl domains for the application in the \"Unfurls\" tab." : "Space administrator needs to approve unfurl domains for the application.";
        return (
            <WarningBox isActionable={false}
                        text={warningText}
                        style={{alignSelf: 'stretch'}}
            />
        );
    }
}
