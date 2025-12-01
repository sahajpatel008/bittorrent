function AlertStack({ alerts, onDismiss }) {
  return (
    <div className="alert-stack">
      {alerts.map((alert) => (
        <div key={alert.id} className={`alert ${alert.variant}`}>
          <span>{alert.message}</span>
          <button onClick={() => onDismiss(alert.id)}>×</button>
        </div>
      ))}
    </div>
  );
}

export default AlertStack;
