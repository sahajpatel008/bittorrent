import './FormControls.css';

export default function FileUploadField({ label = 'Torrent File', onChange }) {
  return (
    <label className="form-field">
      <span>{label}</span>
      <input
        type="file"
        accept=".torrent"
        onChange={(event) => onChange(event.target.files?.[0] ?? null)}
      />
    </label>
  );
}
