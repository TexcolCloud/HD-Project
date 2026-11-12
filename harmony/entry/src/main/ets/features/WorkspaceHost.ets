import { Repair } from "../models/ApiModels";

// UI effects stay at the application shell; feature controllers own their business drafts and requests.
export interface WorkspaceHost {
  setError: (message: string) => void;
  fail: (error: Error) => void;
  flash: (message: string) => void;
  navigate: (page: string) => void;
  openRepair: (repair: Repair) => Promise<void>;
  confirm: (
    title: string,
    message: string,
    cancel: string,
    accept: string,
  ) => Promise<boolean>;
  inform: (title: string, message: string) => Promise<void>;
}

export function emptyWorkspaceHost(): WorkspaceHost {
  return {
    setError: (_message: string): void => {},
    fail: (_error: Error): void => {},
    flash: (_message: string): void => {},
    navigate: (_page: string): void => {},
    openRepair: async (_repair: Repair): Promise<void> => {},
    confirm: async (
      _title: string,
      _message: string,
      _cancel: string,
      _accept: string,
    ): Promise<boolean> => false,
    inform: async (_title: string, _message: string): Promise<void> => {},
  };
}
