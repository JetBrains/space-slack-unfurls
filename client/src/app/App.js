import './App.css';
import {useEffect, useState} from "react";
import * as spaceAuth from "./service/spaceAuth";
import * as slackTeams from "./service/homepageData";
import * as homepageData from "./service/homepageData";
import StartPage from "./components/startPage";
import MainPage from "./components/mainPage";
import Loader from "./components/loader";

export default App;

function App() {
    const [pageSelectorDataLoaded, setPageSelectorDataLoaded] = useState(false);

    useEffect(() => {
        const fetchPageSelectorData = async () => {
            await spaceAuth.fetchSpaceUserToken();
            await homepageData.fetchHomepageData();
            setPageSelectorDataLoaded(true);
        };
        fetchPageSelectorData().catch(console.error);
    }, []);

    return (
        <div className="app">
            <span className="app-header">Slack Link Previews</span>
            <span className="app-description">Previews for links to Slack messages posted in Space. Previews for links to Space messages, issues, etc. posted in Slack.</span>
            {
                pageSelectorDataLoaded
                    ? <PageSelector/>
                    : <Loader/>
            }
        </div>
    );
}

function PageSelector() {
    const [showStartPage, setShowStartPage] = useState(!slackTeams.isSlackTeamPresent());
    slackTeams.setTeamPresentCallback((teamsPresent) => setShowStartPage(!teamsPresent));

    return (
        showStartPage
            ? <StartPage/>
            : <MainPage/>
    );
}
