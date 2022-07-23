import './mainPage.css';
import AddSlackTeamButton from "./addSlackTeamButton";
import ConnectedTeamList from "./connectedTeamList";
import * as homepageData from "../service/homepageData";
import UnapprovedPermissionsAndUnfurlsWarnings from "./unapprovedPermissionsAndUnfurlsWarnings";

export default function MainPage() {
    return (
        <div className="mainPage">
            <UnapprovedPermissionsAndUnfurlsWarnings/>
            <AddSlackTeamButton canManage={homepageData.canManage()}/>
            <ConnectedTeamList canManage={homepageData.canManage()}/>
        </div>
    );
}
