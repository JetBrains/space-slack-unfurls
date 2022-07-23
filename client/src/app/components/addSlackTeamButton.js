import Button from "./button";
import * as slackTeams from "../service/homepageData";

export default function AddSlackTeamButton(props) {
    return (
        <Button buttonText="Add Slack team"
                actionHandler={() => slackTeams.addSlackTeam()}
                isDisabled={!props.canManage}
        />
    );
}
