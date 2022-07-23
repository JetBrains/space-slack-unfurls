import './button.css';
import * as theme from "../service/theme";

export default function Button(props) {
    let disabledClassName = theme.isDark ? "buttonDisabledDark" : "buttonDisabledLight";
    let enabledClassName = theme.isDark ? "buttonEnabledDark" : "buttonEnabledLight"
    let classNames = props.isDisabled ? `button ${disabledClassName}` : `button buttonEnabled ${enabledClassName}`;
    return (
        <button
            className={classNames}
            type="button"
            onClick={() => {
                if (!props.isDisabled) {
                    props.actionHandler()
                }
            }}
        >
            <span className="buttonText">{props.buttonText}</span>
        </button>
    );
}
