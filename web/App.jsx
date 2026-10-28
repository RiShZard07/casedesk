import { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';

async function api(path, data) {
  const options = data === undefined ? {} : {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)};
  const response = await fetch(path, options);
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || `Request failed (${response.status})`);
  return result;
}

function TicketTable({ tickets, busy, onClose }) {
  return <section><h2>Tickets</h2>
    {tickets.length === 0 ? <p>No tickets yet.</p> : <table>
      <thead><tr><th>ID</th><th>Title</th><th>Priority</th><th>State</th><th>Action</th></tr></thead>
      <tbody>{tickets.map(ticket => <tr key={ticket.id}>
        <td>{ticket.id}</td><td>{ticket.title}</td><td>{ticket.priority}</td><td>{ticket.state}</td>
        <td>{ticket.state === 'ASSIGNED' && <button disabled={busy} onClick={() => onClose(ticket.id)}>Close</button>}</td>
      </tr>)}</tbody>
    </table>}
  </section>;
}

function App() {
  const [tickets, setTickets] = useState([]), [metrics, setMetrics] = useState(null);
  const [title, setTitle] = useState(''), [priority, setPriority] = useState('3');
  const [busy, setBusy] = useState(true), [status, setStatus] = useState('Loading…'), [error, setError] = useState('');
  // State updates are asynchronous; this ref also guards rapid repeated events.
  const locked = useRef(true), pendingSubmission = useRef(null);
  async function refresh() {
    const [nextTickets,nextMetrics] = await Promise.all([api('/tickets'),api('/metrics')]);
    setTickets(nextTickets); setMetrics(nextMetrics);
  }
  useEffect(() => {
    let mounted = true;
    Promise.all([api('/tickets'),api('/metrics')]).then(([nextTickets,nextMetrics]) => {
      if (mounted) {setTickets(nextTickets);setMetrics(nextMetrics);setStatus('Ready');}
    }).catch(e => {if(mounted)setError(e.message);}).finally(() => {
      if(mounted){setBusy(false);locked.current=false;}
    });
    return () => {mounted=false;};
  }, []);
  async function act(action) {
    if(locked.current)return;
    locked.current=true;setBusy(true);setError('');setStatus('Working…');
    try{await action();await refresh();setStatus('Done');}
    catch(e){setError(e.message);setStatus('Request failed');}
    finally{locked.current=false;setBusy(false);}
  }
  function create(event) {
    event.preventDefault();
    act(async () => {
      const normalized=title.trim();
      if(!normalized)throw new Error('Enter a ticket title.');
      const prior=pendingSubmission.current;
      const submission=prior?.title===normalized && prior?.priority===priority ? prior : {key:crypto.randomUUID(),title:normalized,priority};
      pendingSubmission.current=submission;
      await api('/tickets',submission);
      pendingSubmission.current=null;setTitle('');
    });
  }
  return <>
    <h1>CaseDesk</h1><p className="note">AI-assisted Java + React prototype. Local, in-memory demo only. Use fictional tickets; data disappears on restart.</p>
    <section><h2>Create a ticket</h2><form onSubmit={create}>
      <label htmlFor="title">Title</label><input id="title" value={title} onChange={e=>setTitle(e.target.value)} maxLength={160} required disabled={busy}/>
      <label htmlFor="priority">Priority</label><select id="priority" value={priority} onChange={e=>setPriority(e.target.value)} disabled={busy}>
        {[1,2,3,4,5].map(p=><option key={p} value={p}>{p}</option>)}
      </select><button type="submit" disabled={busy}>Create</button></form>
      <button disabled={busy} onClick={()=>act(()=>api('/claim',{}))}>Claim highest priority</button>
      <button disabled={busy} onClick={()=>act(async()=>{})}>Refresh</button>
      <p className="message" role="status">{status}</p>{error && <p className="message" role="alert">{error}</p>}
    </section>
    <TicketTable tickets={tickets} busy={busy} onClose={id=>act(()=>api('/close',{id}))}/>
    <section><h2>Process telemetry</h2><pre data-testid="metrics">{metrics?JSON.stringify(metrics,null,2):'Loading…'}</pre>
      <p className="note">Request/error counts, completed-request duration totals, and ticket-state metrics. Not a production benchmark.</p></section>
  </>;
}
createRoot(document.getElementById('root')).render(<App/>);
