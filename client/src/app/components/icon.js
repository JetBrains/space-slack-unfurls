export default function Icon(props) {
    let iconName = props.name;
    if (props.specialIconForDarkTheme) {
        iconName = props.name + `-dark`;
    }

    let style = {};
    if (props.style !== undefined) {
        style = props.style;
    }

    let onClickHandler = () => {};
    if (props.onClickHandler) {
        style.cursor = 'pointer';
        onClickHandler = props.onClickHandler;
    }

    return (
        <img src={`./images/${iconName}.svg`} alt="" style={style} onClick={onClickHandler}/>
    )
}
