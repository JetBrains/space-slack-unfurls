import "./connectedTeamList.css";
import {useState} from "react";
import * as homepageData from "../service/homepageData";
import Icon from "./icon";
import Spring from "./spring";

export default function ConnectedTeamList(props) {
    let [teamList, setTeamList] = useState(homepageData.getTeamList());
    homepageData.setTeamListCallback(setTeamList);

    return (
        <div className="teams-container">
            <span className="teams-header">Connected teams</span>
            {
                teamList.map((team) =>
                    <div key={team.id} className="team-row">
                        <div className="team-separator"/>
                        <div className="team-info">
                            <TeamIconUrl iconUrl={team.iconUrl}/>
                            <div className="team-name-and-domain-container">
                                <span className="team-name">{team.name}</span>
                                <span className="team-domain">{`${team.domain}.slack.com`}</span>
                            </div>
                            <Spring/>
                            {
                                props.canManage &&
                                <Icon name="close" specialIconForDarkTheme={true}
                                      style={{padding: '8px'}}
                                      onClickHandler={() => homepageData.removeSlackTeam(team.id, team.name)}/>
                            }
                        </div>
                    </div>
                )
            }
            {
                teamList.length > 0 && <div className="team-separator"/>
            }
        </div>
    );
}

function TeamIconUrl(props) {
    return (
        props.iconUrl
            ? <img src={props.iconUrl} className="team-icon" alt=""/>
            : <Icon name="slack"/>
    );
}
