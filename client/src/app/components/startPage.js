import "./startPage.css"
import Icon from "./icon";
import AddSlackTeamButton from "./addSlackTeamButton";
import * as homepageData from "../service/homepageData";

export default function StartPage() {
    return (
        <div className="startPage">
            <Icon name="slack-empty" style={{marginTop: '50px', alignSelf: 'center', marginBottom: '25px'}}/>
            <span className="sub-header">No Slack teams connected</span>
            <AddSlackTeamButton canManage={homepageData.canManage()}/>
        </div>
    )
}
