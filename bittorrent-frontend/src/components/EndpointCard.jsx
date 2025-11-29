import './EndpointCard.css';

export default function EndpointCard({ title, description, actions, children }) {
  return (
    <section className="endpoint-card">
      <header>
        <div>
          <h2>{title}</h2>
          {description && <p>{description}</p>}
        </div>
        {actions && <div className="endpoint-actions">{actions}</div>}
      </header>
      <div className="endpoint-body">{children}</div>
    </section>
  );
}
